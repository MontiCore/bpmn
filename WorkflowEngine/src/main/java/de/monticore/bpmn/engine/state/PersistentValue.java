/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.state;

import de.monticore.values.MCValue;
import de.monticore.values.MCValueBoolean;
import de.monticore.values.MCValueDouble;
import de.monticore.values.MCValueInt;
import de.monticore.values.MCValueObject;
import de.se_rwth.commons.logging.Log;
import java.util.Optional;

/**
 * The durable form of one variable value.
 *
 * <p>An {@link MCValue} of the interpreter may wrap an arbitrary Java object, which cannot be
 * stored in general. This class covers the value kinds a process variable can have in a stored
 * instance; everything else is reported and stored as {@code NULL}.
 */
public class PersistentValue {
  
  public enum Kind {
    BOOLEAN, INT, DOUBLE, STRING, NULL
  }
  
  protected final Kind kind;
  
  /** The value in its textual form, {@code null} for {@link Kind#NULL}. */
  protected final String value;
  
  public PersistentValue(final Kind kind, final String value) {
    this.kind = kind;
    this.value = value;
  }
  
  public Kind getKind() { return kind; }
  
  public String getValue() { return value; }
  
  /** Converts a value of the interpreter into its durable form. */
  public static PersistentValue of(final MCValue value) {
    if (value == null || value.isVoid()) {
      return new PersistentValue(Kind.NULL, null);
    }
    if (value.isBoolean()) {
      return new PersistentValue(Kind.BOOLEAN, String.valueOf(value.asBoolean()));
    }
    if (value.isInt()) {
      return new PersistentValue(Kind.INT, String.valueOf(value.asInt()));
    }
    if (value.isDouble()) {
      return new PersistentValue(Kind.DOUBLE, String.valueOf(value.asDouble()));
    }
    final Object nativeValue = value.isObject() ? value.asNativeObject() : null;
    if (nativeValue == null) {
      return new PersistentValue(Kind.NULL, null);
    }
    if (nativeValue instanceof String string) {
      return new PersistentValue(Kind.STRING, string);
    }
    Log.warn("0xWFE0101 A value of type '" + value.printType()
        + "' cannot be persisted yet and is dropped. Extend PersistentValue to support it.");
    return new PersistentValue(Kind.NULL, null);
  }
  
  /** Converts a plain Java value into its durable form. */
  public static PersistentValue of(final Object value) {
    return switch (value) {
      case null -> new PersistentValue(Kind.NULL, null);
      case Boolean b -> new PersistentValue(Kind.BOOLEAN, String.valueOf(b));
      case Integer i -> new PersistentValue(Kind.INT, String.valueOf(i));
      case Long l -> new PersistentValue(Kind.INT, String.valueOf(l.intValue()));
      case Double d -> new PersistentValue(Kind.DOUBLE, String.valueOf(d));
      case Float f -> new PersistentValue(Kind.DOUBLE, String.valueOf(f.doubleValue()));
      case String s -> new PersistentValue(Kind.STRING, s);
      default -> PersistentValue.of(de.monticore.values.MCValueFactory.createMIValueOfNativeObject(
          value));
    };
  }
  
  /** @return the value of the interpreter, empty if the value is not set */
  public Optional<MCValue> toMCValue() {
    return switch (kind) {
      case BOOLEAN -> Optional.of(new MCValueBoolean(Boolean.parseBoolean(value)));
      case INT -> Optional.of(new MCValueInt(Integer.parseInt(value)));
      case DOUBLE -> Optional.of(new MCValueDouble(Double.parseDouble(value)));
      case STRING -> Optional.of(new MCValueObject(value));
      case NULL -> Optional.empty();
    };
  }
  
  /** @return the value as a plain Java object, {@code null} if it is not set */
  public Object toJavaObject() {
    return switch (kind) {
      case BOOLEAN -> Boolean.parseBoolean(value);
      case INT -> Integer.parseInt(value);
      case DOUBLE -> Double.parseDouble(value);
      case STRING -> value;
      case NULL -> null;
    };
  }
  
  @Override
  public String toString() { return kind + "(" + value + ")"; }
  
}
