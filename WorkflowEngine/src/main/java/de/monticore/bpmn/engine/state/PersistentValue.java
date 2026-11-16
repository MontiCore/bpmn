/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * The durable form of the value of one data object.
 *
 * <p>A data object of a process may hold an arbitrary Java object, which cannot be stored in
 * general. A stored instance therefore knows three forms of a value:
 *
 * <ul>
 * <li>simple values: {@code boolean}, {@code int}, {@code double} and {@code String};
 * <li>plain data: maps and lists of simple values, stored as {@link Kind#JSON}. An object of a
 * model type can be given this way, its attributes are the keys of the map;
 * <li>a {@link Kind#REFERENCE} to an object that the application keeps itself, e.g., the key of an
 * entity in its database, cf. {@link DataObjectResolver}.
 * </ul>
 */
public class PersistentValue {
  
  public enum Kind {
    BOOLEAN, INT, DOUBLE, STRING, JSON, REFERENCE, NULL
  }
  
  /** Keeps integers as integers; by default Gson reads every number as a double. */
  protected static final Gson GSON = new GsonBuilder().setObjectToNumberStrategy(
      ToNumberPolicy.LONG_OR_DOUBLE).serializeNulls().create();
  
  protected final Kind kind;
  
  /** The value in its textual form, {@code null} for {@link Kind#NULL}. */
  protected final String value;
  
  public PersistentValue(final Kind kind, final String value) {
    this.kind = kind;
    this.value = value;
  }
  
  public Kind getKind() { return kind; }
  
  public String getValue() { return value; }
  
  /**
   * Converts a plain Java value into its durable form.
   *
   * @param value a simple value or plain data
   * @return the durable form, empty if the value is an object that cannot be stored as data
   */
  public static Optional<PersistentValue> of(final Object value) {
    if (value == null) {
      return Optional.of(new PersistentValue(Kind.NULL, null));
    }
    if (value instanceof Boolean) {
      return Optional.of(new PersistentValue(Kind.BOOLEAN, String.valueOf(value)));
    }
    if (value instanceof Integer || value instanceof Long || value instanceof Short
        || value instanceof Byte) {
      return Optional.of(new PersistentValue(Kind.INT, String.valueOf(((Number) value)
          .intValue())));
    }
    if (value instanceof Number number) {
      return Optional.of(new PersistentValue(Kind.DOUBLE, String.valueOf(number.doubleValue())));
    }
    if (value instanceof String || value instanceof Character) {
      return Optional.of(new PersistentValue(Kind.STRING, String.valueOf(value)));
    }
    if (isPlainData(value)) {
      return Optional.of(new PersistentValue(Kind.JSON, GSON.toJson(value)));
    }
    return Optional.empty();
  }
  
  /** @return the durable form of a reference to an object that the application keeps */
  public static PersistentValue ofReference(final String reference) {
    return new PersistentValue(Kind.REFERENCE, reference);
  }
  
  /** @return whether the value only consists of maps, lists and simple values */
  public static boolean isPlainData(final Object value) {
    if (value == null || value instanceof Boolean || value instanceof Number
        || value instanceof String || value instanceof Character) {
      return true;
    }
    if (value instanceof Map<?, ?> map) {
      return map.entrySet().stream().allMatch(entry -> entry.getKey() instanceof String
          && isPlainData(entry.getValue()));
    }
    if (value instanceof Collection<?> collection) {
      return collection.stream().allMatch(PersistentValue::isPlainData);
    }
    return false;
  }
  
  /**
   * @return the value as a plain Java object: a simple value, a map or a list; {@code null} if it
   * is not set. A reference is returned as it is stored, it is not resolved.
   */
  public Object toJavaObject() {
    return switch (kind) {
      case BOOLEAN -> Boolean.parseBoolean(value);
      case INT -> Integer.parseInt(value);
      case DOUBLE -> Double.parseDouble(value);
      case STRING, REFERENCE -> value;
      case JSON -> GSON.fromJson(value, Object.class);
      case NULL -> null;
    };
  }
  
  @Override
  public String toString() { return kind + "(" + value + ")"; }
  
}
