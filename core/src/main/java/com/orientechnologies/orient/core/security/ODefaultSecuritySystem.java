/*
 *
 *  *  Copyright 2016 OrientDB LTD (info(-at-)orientdb.com)
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *  * For more information: http://orientdb.com
 *
 */
package com.orientechnologies.orient.core.security;

import com.orientechnologies.common.io.OIOUtils;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.common.parser.OSystemVariableResolver;
import com.orientechnologies.orient.core.config.OGlobalConfiguration;
import com.orientechnologies.orient.core.db.OrientDBEmbedded;
import com.orientechnologies.orient.core.db.OrientDBInternal;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.metadata.security.OSecurityInternal;
import com.orientechnologies.orient.core.metadata.security.OSecurityShared;
import com.orientechnologies.orient.core.metadata.security.OSecurityUser;
import com.orientechnologies.orient.core.metadata.security.OSystemUser;
import com.orientechnologies.orient.core.metadata.security.OUser;
import com.orientechnologies.orient.core.record.impl.ODocument;
import java.io.File;
import java.io.FileInputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides an implementation of OServerSecurity.
 *
 * @author S. Colin Leister
 */
public class ODefaultSecuritySystem implements OSecuritySystem {
  private boolean enabled = false; // Defaults to not
  // enabled at
  // first.
  private boolean debug = false;

  private boolean storePasswords = true;

  // OServerSecurity (via OSecurityAuthenticator)
  // Some external security implementations may permit falling back to a
  // default authentication mode if external authentication fails.
  private boolean allowDefault = true;

  private Object passwordValidatorSynch = new Object();
  private OPasswordValidator passwordValidator;

  private Object importLDAPSynch = new Object();
  private OSecurityComponent importLDAP;

  private Object auditingSynch = new Object();
  private OAuditingService auditingService;

  private ODocument configDoc; // Holds the
  // current JSON
  // configuration.
  private OSecurityConfig serverConfig;
  private OrientDBInternal context;

  private ODocument auditingDoc;
  private ODocument serverDoc;
  private ODocument authDoc;
  private ODocument passwdValDoc;
  private ODocument ldapImportDoc;

  // We use a list because the order indicates priority of method.
  private final List<OSecurityAuthenticator> authenticatorsList =
      new ArrayList<OSecurityAuthenticator>();

  private ConcurrentHashMap<String, Class<?>> securityClassMap =
      new ConcurrentHashMap<String, Class<?>>();
  private SecureRandom random = new SecureRandom();

  public ODefaultSecuritySystem() {}

  public ODefaultSecuritySystem(OrientDBEmbedded orientDBEmbedded, OSecurityConfig securityConfig) {
    activate(orientDBEmbedded, securityConfig);
  }

  public void activate(final OrientDBInternal context, final OSecurityConfig serverCfg) {
    this.context = context;
    this.serverConfig = serverCfg;
    if (serverConfig != null) {
      this.load(serverConfig.getConfigurationFile());
    }
    onAfterDynamicPlugins();
  }

  public void shutdown() {
    close();
  }

  private Class<?> getClass(final ODocument jsonConfig) {
    Class<?> cls = null;

    try {
      if (jsonConfig.containsField("class")) {
        final String clsName = jsonConfig.field("class");

        if (securityClassMap.containsKey(clsName)) {
          cls = securityClassMap.get(clsName);
        } else {
          cls = Class.forName(clsName);
        }
      }
    } catch (Exception th) {
      OLogManager.instance().error(this, "ODefaultServerSecurity.getClass() Throwable: ", th);
    }

    return cls;
  }

  // OSecuritySystem (via OServerSecurity)
  // Some external security implementations may permit falling back to a
  // default authentication mode if external authentication fails.
  public boolean isDefaultAllowed() {
    if (isEnabled()) return allowDefault;
    else return true; // If the security system is disabled return the original system default.
  }

  // OSecuritySystem (via OServerSecurity)
  public String authenticate(final String username, final String password) {
    if (isEnabled()) {
      try {
        // It's possible for the username to be null or an empty string in the case of SPNEGO
        // Kerberos
        // tickets.
        if (username != null && !username.isEmpty()) {
          if (debug)
            OLogManager.instance()
                .info(
                    this,
                    "ODefaultServerSecurity.authenticate() ** Authenticating username: %s",
                    username);
        }

        List<OSecurityAuthenticator> active = new ArrayList<>();
        synchronized (authenticatorsList) {
          // Walk through the list of OSecurityAuthenticators.
          for (OSecurityAuthenticator sa : authenticatorsList) {
            if (sa.isEnabled()) {
              active.add(sa);
            }
          }
        }
        for (OSecurityAuthenticator sa : active) {
          String principal = sa.authenticate(username, password);

          if (principal != null) return principal;
        }

      } catch (Exception ex) {
        OLogManager.instance().error(this, "ODefaultServerSecurity.authenticate()", ex);
      }

      return null; // Indicates authentication failed.
    } else {
      return authenticateServerUser(username, password);
    }
  }

  public String authenticateServerUser(final String username, final String password) {
    OGlobalUser user = getServerUser(username);

    if (user != null && user.getPassword() != null) {
      if (OSecurityManager.checkPassword(password, user.getPassword())) {
        return user.getName();
      }
    }
    return null;
  }

  public OrientDBInternal getContext() {
    return context;
  }

  // OSecuritySystem (via OServerSecurity)
  // Used for generating the appropriate HTTP authentication mechanism.
  public String getAuthenticationHeader(final String databaseName) {
    String header = null;

    // Default to Basic.
    if (databaseName != null)
      header = "WWW-Authenticate: Basic realm=\"OrientDB db-" + databaseName + "\"";
    else header = "WWW-Authenticate: Basic realm=\"OrientDB Server\"";

    if (isEnabled()) {
      synchronized (authenticatorsList) {
        StringBuilder sb = new StringBuilder();

        // Walk through the list of OSecurityAuthenticators.
        for (OSecurityAuthenticator sa : authenticatorsList) {
          if (sa.isEnabled()) {
            String sah = sa.getAuthenticationHeader(databaseName);

            if (sah != null && sah.trim().length() > 0) {
              // If we're not the first authenticator, then append "\n".
              if (sb.length() > 0) {
                sb.append("\r\n");
              }
              sb.append(sah);
            }
          }
        }

        if (sb.length() > 0) {
          header = sb.toString();
        }
      }
    }

    return header;
  }

  @Override
  public Map<String, String> getAuthenticationHeaders(String databaseName) {
    Map<String, String> headers = new HashMap<>();

    // Default to Basic.
    if (databaseName != null)
      headers.put("WWW-Authenticate", "Basic realm=\"OrientDB db-" + databaseName + "\"");
    else headers.put("WWW-Authenticate", "Basic realm=\"OrientDB Server\"");

    if (isEnabled()) {
      synchronized (authenticatorsList) {

        // Walk through the list of OSecurityAuthenticators.
        for (OSecurityAuthenticator sa : authenticatorsList) {
          if (sa.isEnabled()) {
            Map<String, String> currentHeaders = sa.getAuthenticationHeaders(databaseName);
            currentHeaders
                .entrySet()
                .forEach(entry -> headers.put(entry.getKey(), entry.getValue()));
          }
        }
      }
    }

    return headers;
  }

  // OSecuritySystem (via OServerSecurity)
  public ODocument getConfig() {
    ODocument jsonConfig = new ODocument();

    try {
      jsonConfig.field("enabled", enabled);
      jsonConfig.field("debug", debug);

      if (serverDoc != null) {
        jsonConfig.field("server", serverDoc, OType.EMBEDDED);
      }

      if (authDoc != null) {
        jsonConfig.field("authentication", authDoc, OType.EMBEDDED);
      }

      if (passwdValDoc != null) {
        jsonConfig.field("passwordValidator", passwdValDoc, OType.EMBEDDED);
      }

      if (ldapImportDoc != null) {
        jsonConfig.field("ldapImporter", ldapImportDoc, OType.EMBEDDED);
      }

      if (auditingDoc != null) {
        jsonConfig.field("auditing", auditingDoc, OType.EMBEDDED);
      }
    } catch (Exception ex) {
      OLogManager.instance().error(this, "ODefaultServerSecurity.getConfig() Exception: %s", ex);
    }

    return jsonConfig;
  }

  // OSecuritySystem (via OServerSecurity)
  // public ODocument getComponentConfig(final String name) { return getSection(name); }

  public ODocument getComponentConfig(final String name) {
    if (name != null) {
      if (name.equalsIgnoreCase("auditing")) {
        return auditingDoc;
      } else if (name.equalsIgnoreCase("authentication")) {
        return authDoc;
      } else if (name.equalsIgnoreCase("ldapImporter")) {
        return ldapImportDoc;
      } else if (name.equalsIgnoreCase("passwordValidator")) {
        return passwdValDoc;
      } else if (name.equalsIgnoreCase("server")) {
        return serverDoc;
      }
    }

    return null;
  }

  // OServerSecurity

  /**
   * Returns the "System User" associated with 'username' from the system database. If not found,
   * returns null. dbName is used to filter the assigned roles. It may be null.
   */
  public OUser getSystemUser(final String username, final String dbName) {
    // ** There are cases when we need to retrieve an OUser that is a system user.
    //  if (isEnabled() && !OSystemDatabase.SYSTEM_DB_NAME.equals(dbName)) {
    if (isEnabled()) {
      return (OUser)
          context
              .getSystemDatabase()
              .execute(
                  (resultset) -> {
                    if (resultset != null && resultset.hasNext())
                      return new OSystemUser(
                          (ODocument) resultset.next().getElement().get().getRecord(), dbName);
                    return null;
                  },
                  "select from OUser where name = ? limit 1 fetchplan roles:1",
                  username);
    }
    return null;
  }

  // OSecuritySystem (via OServerSecurity)
  // This will first look for a user in the security.json "users" array and then check if a resource
  // matches.
  public boolean isAuthorized(final String username, final String resource) {
    if (isEnabled()) {
      if (username == null || resource == null) return false;

      synchronized (authenticatorsList) {
        // Walk through the list of OSecurityAuthenticators.
        for (OSecurityAuthenticator sa : authenticatorsList) {
          if (sa.isEnabled()) {
            if (sa.isAuthorized(username, resource)) return true;
          }
        }
      }
      return false;
    } else {
      return isServerUserAuthorized(username, resource);
    }
  }

  public boolean isServerUserAuthorized(final String username, final String resource) {
    final OGlobalUser user = getServerUser(username);

    if (user != null) {
      if (user.getResources().equals("*"))
        // ACCESS TO ALL
        return true;

      String[] resourceParts = user.getResources().split(",");
      for (String r : resourceParts) if (r.equalsIgnoreCase(resource)) return true;
    }
    return false;
  }

  // OSecuritySystem (via OServerSecurity)
  public boolean isEnabled() {
    return enabled;
  }

  // OSecuritySystem (via OServerSecurity)
  // Indicates if passwords should be stored for users.
  public boolean arePasswordsStored() {
    if (isEnabled()) return storePasswords;
    else return true; // If the security system is disabled return the original system default.
  }

  // OSecuritySystem (via OServerSecurity)
  // Indicates if the primary security mechanism supports single sign-on.
  public boolean isSingleSignOnSupported() {
    if (isEnabled()) {
      OSecurityAuthenticator priAuth = getPrimaryAuthenticator();

      if (priAuth != null) return priAuth.isSingleSignOnSupported();
    }

    return false;
  }

  // OSecuritySystem (via OServerSecurity)
  public void validatePassword(final String username, final String password)
      throws OInvalidPasswordException {
    if (isEnabled()) {
      synchronized (passwordValidatorSynch) {
        if (passwordValidator != null) {
          passwordValidator.validatePassword(username, password);
        }
      }
    }
  }

  public void replacePasswordValidator(OPasswordValidator validator) {
    synchronized (passwordValidatorSynch) {
      if (passwordValidator == null || !passwordValidator.isEnabled()) {
        passwordValidator = validator;
      }
    }
  }

  /** * OServerSecurity Interface * */

  // OServerSecurity
  public OAuditingService getAuditing() {
    return auditingService;
  }

  // OServerSecurity
  public OSecurityAuthenticator getAuthenticator(final String authMethod) {
    if (isEnabled()) {
      synchronized (authenticatorsList) {
        for (OSecurityAuthenticator am : authenticatorsList) {
          // If authMethod is null or an empty string, then return the first OSecurityAuthenticator.
          if (authMethod == null || authMethod.isEmpty()) return am;

          if (am.getName() != null && am.getName().equalsIgnoreCase(authMethod)) return am;
        }
      }
    }

    return null;
  }

  // OServerSecurity
  // Returns the first OSecurityAuthenticator in the list.
  public OSecurityAuthenticator getPrimaryAuthenticator() {
    if (isEnabled()) {
      synchronized (authenticatorsList) {
        if (authenticatorsList.size() > 0) return authenticatorsList.get(0);
      }
    }

    return null;
  }

  // OServerSecurity
  public OGlobalUser getUser(final String username) {
    OGlobalUser userCfg = null;

    if (isEnabled()) {

      synchronized (authenticatorsList) {
        // Walk through the list of OSecurityAuthenticators.
        for (OSecurityAuthenticator sa : authenticatorsList) {
          if (sa.isEnabled()) {
            userCfg = sa.getUser(username);
            if (userCfg != null) break;
          }
        }
      }
    } else {
      userCfg = getServerUser(username);
    }

    return userCfg;
  }

  public OGlobalUser getServerUser(final String username) {
    OGlobalUser userCfg = null;
    // This will throw an IllegalArgumentException if iUserName is null or empty.
    // However, a null or empty iUserName is possible with some security implementations.
    if (serverConfig != null && serverConfig.usersManagement()) {
      if (username != null && !username.isEmpty()) userCfg = serverConfig.getUser(username);
    }
    return userCfg;
  }

  @Override
  public OSyslog getSyslog() {
    return serverConfig.getSyslog();
  }

  // OSecuritySystem
  public void log(
      final OAuditingOperation operation,
      final String dbName,
      OSecurityUser user,
      final String message) {
    synchronized (auditingSynch) {
      if (auditingService != null) auditingService.log(operation, dbName, user, message);
    }
  }

  // OSecuritySystem
  public void registerSecurityClass(final Class<?> cls) {
    String fullTypeName = getFullTypeName(cls);

    if (fullTypeName != null) {
      securityClassMap.put(fullTypeName, cls);
    }
  }

  // OSecuritySystem
  public void unregisterSecurityClass(final Class<?> cls) {
    String fullTypeName = getFullTypeName(cls);

    if (fullTypeName != null) {
      securityClassMap.remove(fullTypeName);
    }
  }

  // Returns the package plus type name of Class.
  private static String getFullTypeName(Class<?> type) {
    String typeName = null;

    typeName = type.getSimpleName();

    Package pack = type.getPackage();

    if (pack != null) {
      typeName = pack.getName() + "." + typeName;
    }

    return typeName;
  }

  public void load(final String cfgPath) {
    this.configDoc = loadConfig(cfgPath);
  }

  // OSecuritySystem
  public void reload(final String cfgPath) {
    reload(null, cfgPath);
  }

  @Override
  public void reload(OSecurityUser user, String cfgPath) {
    reload(user, loadConfig(cfgPath));
  }

  // OSecuritySystem
  public void reload(final ODocument configDoc) {
    reload(null, configDoc);
  }

  @Override
  public void reload(OSecurityUser user, ODocument configDoc) {
    if (configDoc != null) {
      close();

      this.configDoc = configDoc;

      onAfterDynamicPlugins(user);

      log(
          OAuditingOperation.RELOADEDSECURITY,
          null,
          user,
          "The security configuration file has been reloaded");
    } else {
      OLogManager.instance()
          .warn(
              this,
              "ODefaultServerSecurity.reload(ODocument) The provided configuration document is null");
      throw new OSecuritySystemException(
          "ODefaultServerSecurity.reload(ODocument) The provided configuration document is null");
    }
  }

  public void reloadComponent(OSecurityUser user, final String name, final ODocument jsonConfig) {
    if (name == null || name.isEmpty())
      throw new OSecuritySystemException(
          "ODefaultServerSecurity.reloadComponent() name is null or empty");
    if (jsonConfig == null)
      throw new OSecuritySystemException(
          "ODefaultServerSecurity.reloadComponent() Configuration document is null");

    if (name.equalsIgnoreCase("auditing")) {
      auditingDoc = jsonConfig;
      reloadAuditingService();

    } else if (name.equalsIgnoreCase("authentication")) {
      authDoc = jsonConfig;
      reloadAuthMethods();

    } else if (name.equalsIgnoreCase("ldapImporter")) {
      ldapImportDoc = jsonConfig;
      reloadImportLDAP();
    } else if (name.equalsIgnoreCase("passwordValidator")) {
      passwdValDoc = jsonConfig;
      reloadPasswordValidator();
    } else if (name.equalsIgnoreCase("server")) {
      serverDoc = jsonConfig;
      reloadServer();
    }
    setSection(name, jsonConfig);

    log(
        OAuditingOperation.RELOADEDSECURITY,
        null,
        user,
        String.format("The %s security component has been reloaded", name));
  }

  private void loadAuthenticators(final ODocument authDoc) {
    synchronized (authenticatorsList) {
      for (OSecurityAuthenticator sa : authenticatorsList) {
        sa.dispose();
      }

      authenticatorsList.clear();

      if (authDoc.containsField("authenticators")) {
        List<ODocument> authMethodsList = authDoc.field("authenticators");

        for (ODocument authMethodDoc : authMethodsList) {
          try {
            if (authMethodDoc.containsField("name")) {
              final String name = authMethodDoc.field("name");

              // defaults to enabled if "enabled" is missing
              boolean enabled = true;

              if (authMethodDoc.containsField("enabled")) enabled = authMethodDoc.field("enabled");

              if (enabled) {
                Class<?> authClass = getClass(authMethodDoc);

                if (authClass != null) {
                  if (OSecurityAuthenticator.class.isAssignableFrom(authClass)) {
                    OSecurityAuthenticator authPlugin =
                        (OSecurityAuthenticator) authClass.newInstance();

                    authPlugin.config(authMethodDoc, this);
                    authPlugin.active();

                    authenticatorsList.add(authPlugin);
                  } else {
                    OLogManager.instance()
                        .error(
                            this,
                            "ODefaultServerSecurity.loadAuthenticators() class is not an OSecurityAuthenticator",
                            null);
                  }
                } else {
                  OLogManager.instance()
                      .error(
                          this,
                          "ODefaultServerSecurity.loadAuthenticators() authentication class is null for %s",
                          null,
                          name);
                }
              }
            } else {
              OLogManager.instance()
                  .error(
                      this,
                      "ODefaultServerSecurity.loadAuthenticators() authentication object is missing name",
                      null);
            }
          } catch (Exception ex) {
            OLogManager.instance()
                .error(this, "ODefaultServerSecurity.loadAuthenticators() Exception: ", ex);
          }
        }
      }
    }
  }

  // OServerSecurity
  public void onAfterDynamicPlugins() {
    onAfterDynamicPlugins(null);
  }

  @Override
  public void onAfterDynamicPlugins(OSecurityUser user) {
    if (configDoc != null) {
      loadComponents();

      if (isEnabled()) {
        log(OAuditingOperation.SECURITY, null, user, "The security module is now loaded");
      }
    } else {
      OLogManager.instance().warn(this, "onAfterDynamicPlugins() Configuration document is empty");
    }
  }

  protected void loadComponents() {
    // Loads the top-level configuration properties ("enabled" and "debug").
    loadSecurity();

    if (isEnabled()) {
      // Loads the "auditing" configuration properties.
      auditingDoc = getSection("auditing");
      reloadAuditingService();

      // Loads the "server" configuration properties.
      serverDoc = getSection("server");
      reloadServer();

      // Loads the "authentication" configuration properties.
      authDoc = getSection("authentication");
      reloadAuthMethods();

      // Loads the "passwordValidator" configuration properties.
      passwdValDoc = getSection("passwordValidator");
      reloadPasswordValidator();

      // Loads the "ldapImporter" configuration properties.
      ldapImportDoc = getSection("ldapImporter");
      reloadImportLDAP();
    }
  }

  // Returns a section of the JSON document configuration as an ODocument if section is present.
  private ODocument getSection(final String section) {
    ODocument sectionDoc = null;

    try {
      if (configDoc != null) {
        if (configDoc.containsField(section)) {
          sectionDoc = configDoc.field(section);
        }
      } else {
        OLogManager.instance()
            .error(
                this,
                "ODefaultServerSecurity.getSection(%s) Configuration document is null",
                null,
                section);
      }
    } catch (Exception ex) {
      OLogManager.instance().error(this, "ODefaultServerSecurity.getSection(%s)", ex, section);
    }

    return sectionDoc;
  }

  // Change the component section and save it to disk
  private void setSection(final String section, ODocument sectionDoc) {

    ODocument oldSection = getSection(section);
    try {
      if (configDoc != null) {

        configDoc.field(section, sectionDoc);
        String configFile =
            OSystemVariableResolver.resolveSystemVariables("${ORIENTDB_HOME}/config/security.json");

        String ssf = OGlobalConfiguration.SERVER_SECURITY_FILE.getValueAsString();
        if (ssf != null) configFile = ssf;

        File f = new File(configFile);
        OIOUtils.writeFile(f, configDoc.toJSON("prettyPrint"));
      }
    } catch (Exception ex) {
      configDoc.field(section, oldSection);
      OLogManager.instance().error(this, "ODefaultServerSecurity.setSection(%s)", ex, section);
    }
  }

  // "${ORIENTDB_HOME}/config/security.json"
  private ODocument loadConfig(final String cfgPath) {
    ODocument securityDoc = null;

    try {
      if (cfgPath != null) {
        // Default
        String jsonFile = OSystemVariableResolver.resolveSystemVariables(cfgPath);

        File file = new File(jsonFile);

        if (file.exists() && file.canRead()) {
          FileInputStream fis = null;

          try {
            fis = new FileInputStream(file);

            final byte[] buffer = new byte[(int) file.length()];
            fis.read(buffer);

            securityDoc = (ODocument) new ODocument().fromJSON(new String(buffer), "noMap");
          } finally {
            if (fis != null) fis.close();
          }
        } else {
          OLogManager.instance()
              .error(
                  this,
                  "ODefaultServerSecurity.loadConfig() Could not access the security JSON file: %s",
                  null,
                  jsonFile);
        }
      } else {
        OLogManager.instance()
            .error(
                this, "ODefaultServerSecurity.loadConfig() Configuration file path is null", null);
      }
    } catch (Exception ex) {
      OLogManager.instance().error(this, "ODefaultServerSecurity.loadConfig()", ex);
    }

    return securityDoc;
  }

  private boolean isEnabled(final ODocument sectionDoc) {
    boolean enabled = true;

    try {
      if (sectionDoc.containsField("enabled")) {
        enabled = sectionDoc.field("enabled");
      }
    } catch (Exception ex) {
      OLogManager.instance().error(this, "ODefaultServerSecurity.isEnabled()", ex);
    }

    return enabled;
  }

  private void loadSecurity() {
    try {
      enabled = false;

      if (configDoc != null) {
        if (configDoc.containsField("enabled")) {
          enabled = configDoc.field("enabled");
        }

        if (configDoc.containsField("debug")) {
          debug = configDoc.field("debug");
        }
      } else {
        OLogManager.instance()
            .error(this, "ODefaultServerSecurity.loadSecurity() jsonConfig is null", null);
      }
    } catch (Exception ex) {
      OLogManager.instance().error(this, "ODefaultServerSecurity.loadSecurity()", ex);
    }
  }

  private void reloadServer() {
    try {
      storePasswords = true;

      if (serverDoc != null) {
        if (serverDoc.containsField("createDefaultUsers")) {
          OGlobalConfiguration.CREATE_DEFAULT_USERS.setValue(serverDoc.field("createDefaultUsers"));
        }

        if (serverDoc.containsField("storePasswords")) {
          storePasswords = serverDoc.field("storePasswords");
        }
      }
    } catch (Exception ex) {
      OLogManager.instance().error(this, "ODefaultServerSecurity.loadServer()", ex);
    }
  }

  private void reloadAuthMethods() {
    if (authDoc != null) {
      if (authDoc.containsField("allowDefault")) {
        allowDefault = authDoc.field("allowDefault");
      }

      loadAuthenticators(authDoc);
    }
  }

  private void reloadPasswordValidator() {
    try {
      synchronized (passwordValidatorSynch) {
        if (passwdValDoc != null && isEnabled(passwdValDoc)) {

          if (passwordValidator != null) {
            passwordValidator.dispose();
            passwordValidator = null;
          }

          Class<?> cls = getClass(passwdValDoc);

          if (cls != null) {
            if (OPasswordValidator.class.isAssignableFrom(cls)) {
              passwordValidator = (OPasswordValidator) cls.newInstance();
              passwordValidator.config(passwdValDoc, this);
              passwordValidator.active();
            } else {
              OLogManager.instance()
                  .error(
                      this,
                      "ODefaultServerSecurity.reloadPasswordValidator() class is not an OPasswordValidator",
                      null);
            }
          } else {
            OLogManager.instance()
                .error(
                    this,
                    "ODefaultServerSecurity.reloadPasswordValidator() PasswordValidator class property is missing",
                    null);
          }
        }
      }
    } catch (Exception ex) {
      OLogManager.instance().error(this, "ODefaultServerSecurity.reloadPasswordValidator()", ex);
    }
  }

  private void reloadImportLDAP() {
    try {
      synchronized (importLDAPSynch) {
        if (importLDAP != null) {
          importLDAP.dispose();
          importLDAP = null;
        }

        if (ldapImportDoc != null && isEnabled(ldapImportDoc)) {
          Class<?> cls = getClass(ldapImportDoc);

          if (cls != null) {
            if (OSecurityComponent.class.isAssignableFrom(cls)) {
              importLDAP = (OSecurityComponent) cls.newInstance();
              importLDAP.config(ldapImportDoc, this);
              importLDAP.active();
            } else {
              OLogManager.instance()
                  .error(
                      this,
                      "ODefaultServerSecurity.reloadImportLDAP() class is not an OSecurityComponent",
                      null);
            }
          } else {
            OLogManager.instance()
                .error(
                    this,
                    "ODefaultServerSecurity.reloadImportLDAP() ImportLDAP class property is missing",
                    null);
          }
        }
      }
    } catch (Exception ex) {
      OLogManager.instance().error(this, "ODefaultServerSecurity.reloadImportLDAP()", ex);
    }
  }

  private void reloadAuditingService() {
    try {
      synchronized (auditingSynch) {
        if (auditingService != null) {
          auditingService.dispose();
          auditingService = null;
        }

        if (auditingDoc != null && isEnabled(auditingDoc)) {
          Class<?> cls = getClass(auditingDoc);

          if (cls != null) {
            if (OAuditingService.class.isAssignableFrom(cls)) {
              auditingService = (OAuditingService) cls.newInstance();
              auditingService.config(auditingDoc, this);
              auditingService.active();
            } else {
              OLogManager.instance()
                  .error(
                      this,
                      "ODefaultServerSecurity.reloadAuditingService() class is not an OAuditingService",
                      null);
            }
          } else {
            OLogManager.instance()
                .error(
                    this,
                    "ODefaultServerSecurity.reloadAuditingService() Auditing class property is missing",
                    null);
          }
        }
      }
    } catch (Exception ex) {
      OLogManager.instance().error(this, "ODefaultServerSecurity.reloadAuditingService()", ex);
    }
  }

  public void close() {
    if (enabled) {

      synchronized (importLDAPSynch) {
        if (importLDAP != null) {
          importLDAP.dispose();
          importLDAP = null;
        }
      }

      synchronized (passwordValidatorSynch) {
        if (passwordValidator != null) {
          passwordValidator.dispose();
          passwordValidator = null;
        }
      }

      synchronized (auditingSynch) {
        if (auditingService != null) {
          auditingService.dispose();
          auditingService = null;
        }
      }

      synchronized (authenticatorsList) {
        // Notify all the security components that the server is active.
        for (OSecurityAuthenticator sa : authenticatorsList) {
          sa.dispose();
        }

        authenticatorsList.clear();
      }

      enabled = false;
    }
  }

  @Override
  public OGlobalUser authenticateAndAuthorize(
      String iUserName, String iPassword, String iResourceToCheck) {
    // Returns the authenticated username, if successful, otherwise null.
    String authUsername = authenticate(iUserName, iPassword);

    // Authenticated, now see if the user is authorized.
    if (authUsername != null) {
      if (isAuthorized(authUsername, iResourceToCheck)) {
        return getUser(authUsername);
      }
    }
    return null;
  }

  public boolean existsUser(String user) {
    if (serverConfig != null && serverConfig.usersManagement()) {
      return serverConfig.existsUser(user);
    } else {
      return false;
    }
  }

  public void addUser(String user, String password, String permissions) {
    if (password == null) {
      // AUTO GENERATE PASSWORD
      final byte[] buffer = new byte[32];
      random.nextBytes(buffer);
      password = OSecurityManager.createSHA256(OSecurityManager.byteArrayToHexStr(buffer));
    }

    // HASH THE PASSWORD
    password =
        OSecurityManager.createHash(
            password,
            context
                .getConfigurations()
                .getConfigurations()
                .getValueAsString(OGlobalConfiguration.SECURITY_USER_PASSWORD_DEFAULT_ALGORITHM),
            true);

    serverConfig.setUser(user, password, permissions);
    serverConfig.saveConfiguration();
  }

  public void dropUser(String iUserName) {
    serverConfig.dropUser(iUserName);
    serverConfig.saveConfiguration();
  }

  public void addTemporaryUser(String iName, String iPassword, String iPermissions) {
    serverConfig.setEphemeralUser(iName, iPassword, iPermissions);
  }

  @Override
  public OSecurityInternal newSecurity(String database) {
    return new OSecurityShared(this);
  }
}
