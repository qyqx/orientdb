/* Generated By:JJTree: Do not edit this line. OCreateSequenceStatement.java Version 4.3 */
/* JavaCCOptions:MULTI=true,NODE_USES_PARSER=false,VISITOR=true,TRACK_TOKENS=true,NODE_PREFIX=O,NODE_EXTENDS=,NODE_FACTORY=,SUPPORT_CLASS_VISIBILITY_PUBLIC=true */
package com.orientechnologies.orient.core.sql.parser;

import com.orientechnologies.orient.core.command.OCommandContext;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.exception.OCommandExecutionException;
import com.orientechnologies.orient.core.metadata.sequence.OSequence;
import com.orientechnologies.orient.core.metadata.sequence.SequenceOrderType;
import com.orientechnologies.orient.core.sql.executor.OInternalResultSet;
import com.orientechnologies.orient.core.sql.executor.OResultInternal;
import com.orientechnologies.orient.core.sql.executor.OResultSet;

import java.util.Map;

public class OCreateSequenceStatement extends OSimpleExecStatement {
  public static final int TYPE_CACHED  = 0;
  public static final int TYPE_ORDERED = 1;  
  
  OIdentifier name;

  public boolean ifNotExists = false;
  
  int         type;
  OExpression start;
  OExpression increment;
  OExpression cache;
  boolean     positive = OSequence.DEFAULT_ORDER_TYPE == SequenceOrderType.ORDER_POSITIVE;
  boolean     cyclic = OSequence.DEFAULT_RECYCLABLE_VALUE;
  OExpression minValue;
  OExpression maxValue;
  
  public OCreateSequenceStatement(int id) {
    super(id);
  }

  public OCreateSequenceStatement(OrientSql p, int id) {
    super(p, id);
  }

  @Override
  public OResultSet executeSimple(OCommandContext ctx) {
    OSequence seq = ctx.getDatabase().getMetadata().getSequenceLibrary().getSequence(this.name.getStringValue());
    if (seq != null) {
      if (ifNotExists) {
        return new OInternalResultSet();
      } else {
        throw new OCommandExecutionException("Sequence " + name.getStringValue() + " already exists");
      }

    }
    OResultInternal result = new OResultInternal();
    result.setProperty("operation", "create sequence");
    result.setProperty("name", name.getStringValue());

    executeInternal(ctx, result);

    OInternalResultSet rs = new OInternalResultSet();
    rs.add(result);
    return rs;
  }

  private void executeInternal(OCommandContext ctx, OResultInternal result) {
    OSequence.CreateParams params = createParams(ctx, result);
    OSequence.SEQUENCE_TYPE seqType = type == TYPE_CACHED ? OSequence.SEQUENCE_TYPE.CACHED : OSequence.SEQUENCE_TYPE.ORDERED;
    result.setProperty("type", seqType.toString());
    ctx.getDatabase().getMetadata().getSequenceLibrary().createSequence(this.name.getStringValue(), seqType, params);
  }

  private OSequence.CreateParams createParams(OCommandContext ctx, OResultInternal result) {
    OSequence.CreateParams params = new OSequence.CreateParams();
    if (start != null) {
      Object o = start.execute((OIdentifiable) null, ctx);
      if (o instanceof Number) {
        params.setStart(((Number) o).longValue());
        result.setProperty("start", o);
      } else {
        throw new OCommandExecutionException("Invalid start value: " + o);
      }
    }
    if (increment != null) {
      Object o = increment.execute((OIdentifiable) null, ctx);
      if (o instanceof Number) {
        params.setIncrement(((Number) o).intValue());
        result.setProperty("increment", o);
      } else {
        throw new OCommandExecutionException("Invalid increment value: " + o);
      }
    }
    if (cache != null) {
      Object o = cache.execute((OIdentifiable) null, ctx);
      if (o instanceof Number) {
        params.setCacheSize(((Number) o).intValue());
        result.setProperty("cacheSize", o);
      } else {
        throw new OCommandExecutionException("Invalid cache value: " + o);
      }
    }
    
    if (minValue != null) {
      Object o = minValue.execute((OIdentifiable) null, ctx);
      if (o instanceof Number) {
        params.setLimitValue(((Number) o).intValue());
        result.setProperty("limitValue", o);
      } else {
        throw new OCommandExecutionException("Invalid limit value: " + o);
      }
    }
    
    if (maxValue != null) {
      Object o = maxValue.execute((OIdentifiable) null, ctx);
      if (o instanceof Number) {
        params.setLimitValue(((Number) o).intValue());
        result.setProperty("limitValue", o);
      } else {
        throw new OCommandExecutionException("Invalid limit value: " + o);
      }
    }
    
    params.setOrderType(positive ? SequenceOrderType.ORDER_POSITIVE : SequenceOrderType.ORDER_NEGATIVE);
    params.setRecyclable(cyclic);
    
    return params;
  }

  @Override
  public void toString(Map<Object, Object> params, StringBuilder builder) {
    builder.append("CREATE SEQUENCE ");
    name.toString(params, builder);
    if (ifNotExists) {
      builder.append(" IF NOT EXISTS");
    }
    builder.append(" TYPE ");
    switch (type) {
    case TYPE_CACHED:
      builder.append(" CACHED");
      break;
    case TYPE_ORDERED:
      builder.append(" ORDERED");
      break;
    default:
      throw new IllegalStateException("Invalid type for CREATE SEQUENCE: " + type);
    }

    if (start != null) {
      builder.append(" START ");
      start.toString(params, builder);
    }
    if (increment != null) {
      builder.append(" INCREMENT ");
      increment.toString(params, builder);
    }
    if (cache != null) {
      builder.append(" CACHE ");
      cache.toString(params, builder);
    }
    if (minValue != null) {
      builder.append(" MINVALUE ");
      minValue.toString(params, builder);
    }
    if (maxValue != null) {
      builder.append(" MAXVALUE ");
      maxValue.toString(params, builder);
    }
    if (cyclic != OSequence.DEFAULT_RECYCLABLE_VALUE) {
      builder.append(" CYCLE");
    }
    if (positive){
      builder.append(" ASC");
    }
    else{
      builder.append(" DESC");
    }
  }

  @Override
  public OCreateSequenceStatement copy() {
    OCreateSequenceStatement result = new OCreateSequenceStatement(-1);
    result.name = name == null ? null : name.copy();
    result.ifNotExists = this.ifNotExists;
    result.type = type;
    result.start = start == null ? null : start.copy();
    result.increment = increment == null ? null : increment.copy();
    result.cache = cache == null ? null : cache.copy();
    result.minValue = minValue == null ? null : minValue.copy();
    result.maxValue = maxValue == null ? null : maxValue.copy();
    result.cyclic = cyclic;
    result.positive = positive;
    return result;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o)
      return true;
    if (o == null || getClass() != o.getClass())
      return false;

    OCreateSequenceStatement that = (OCreateSequenceStatement) o;

    if (ifNotExists != that.ifNotExists)
      return false;
    if (type != that.type)
      return false;
    if (name != null ? !name.equals(that.name) : that.name != null)
      return false;
    if (start != null ? !start.equals(that.start) : that.start != null)
      return false;
    if (increment != null ? !increment.equals(that.increment) : that.increment != null)
      return false;
    if (cache != null ? !cache.equals(that.cache) : that.cache != null)
      return false;
    if (maxValue != null ? !maxValue.equals(that.maxValue) : that.maxValue != null)
      return false;
    if (minValue != null ? !minValue.equals(that.minValue) : that.minValue != null)
      return false;
    if (cyclic != that.cyclic){
      return false;
    }
    return positive == that.positive;
  }

  @Override
  public int hashCode() {
    int result = name != null ? name.hashCode() : 0;
    result = 31 * result + (ifNotExists ? 1 : 0);
    result = 31 * result + type;
    result = 31 * result + (start != null ? start.hashCode() : 0);
    result = 31 * result + (increment != null ? increment.hashCode() : 0);
    result = 31 * result + (cache != null ? cache.hashCode() : 0);
    result = 31 * result + (maxValue != null ? maxValue.hashCode() : 0);
    result = 31 * result + (minValue != null ? minValue.hashCode() : 0);
    result = 31 * result + Boolean.hashCode(cyclic);
    result = 31 * result + Boolean.hashCode(positive);
    return result;
  }
}
/* JavaCC - OriginalChecksum=b0436d11e05c3435f22dafea6b5106c0 (do not edit this line) */
