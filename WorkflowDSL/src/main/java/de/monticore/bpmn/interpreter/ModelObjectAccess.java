/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.interpreter;

import de.monticore.values.MCValue;
import de.monticore.values.MCValueFactory;
import de.monticore.values.MCValueObject;
import de.monticore.values.MCValueVoid;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads attributes and calls methods of the objects that a process works on.
 *
 * <p>The type of a data object is given by a model, e.g., a class of a class diagram. Which Java
 * class represents that type at runtime is decided by the application that embeds the interpreter,
 * not by the process model. This class therefore does not rely on a specific Java class but on
 * conventions:
 *
 * <ul>
 * <li>a {@link Map} is read by key, so an object can be given as plain data, e.g., from JSON;
 * <li>any other object is read via its getter ({@code getX()}, {@code isX()} or {@code x()}) or
 * its public field {@code x}, which covers generated domain classes, hand-written classes and
 * records.
 * </ul>
 */
public final class ModelObjectAccess {
  
  private ModelObjectAccess() {}
  
  /**
   * @param target the object to read from
   * @param attribute the name of the attribute in the model
   * @return the value of the attribute, {@code null} if it has none
   * @throws IllegalStateException if the object has no such attribute
   */
  public static Object get(final Object target, final String attribute) {
    if (target == null) {
      throw new IllegalStateException("The attribute '" + attribute
          + "' cannot be read, the object it belongs to has no value.");
    }
    if (target instanceof Map<?, ?> map) {
      if (!map.containsKey(attribute)) {
        throw new IllegalStateException("The object has no attribute '" + attribute + "'; it has "
            + map.keySet() + ".");
      }
      return unwrap(map.get(attribute));
    }
    final String capitalized = Character.toUpperCase(attribute.charAt(0)) + attribute.substring(1);
    for (String getter : List.of("get" + capitalized, "is" + capitalized, attribute)) {
      final Optional<Method> method = findMethod(target.getClass(), getter, new Object[0]);
      if (method.isPresent()) {
        return unwrap(call(method.get(), target, new Object[0]));
      }
    }
    try {
      final Field field = target.getClass().getField(attribute);
      return unwrap(field.get(target));
    }
    catch (NoSuchFieldException | IllegalAccessException e) {
      throw new IllegalStateException("An object of the class '" + target.getClass().getName()
          + "' has neither a getter nor a public field for the attribute '" + attribute + "'.");
    }
  }
  
  /**
   * @param target the object to call the method of
   * @param name the name of the method in the model
   * @param arguments the arguments of the call
   * @return the result of the call, {@code null} for methods without a result
   * @throws IllegalStateException if the object has no such method or the call fails
   */
  public static Object invoke(final Object target, final String name, final Object[] arguments) {
    if (target == null) {
      throw new IllegalStateException("The method '" + name
          + "' cannot be called, the object it belongs to has no value.");
    }
    final Method method = findMethod(target.getClass(), name, arguments).orElseThrow(
        () -> new IllegalStateException("An object of the class '" + target.getClass().getName()
            + "' has no public method '" + name + "' for " + arguments.length + " argument(s)."));
    return unwrap(call(method, target, arguments));
  }
  
  /** @return the value as the interpreter represents it */
  public static MCValue toValue(final Object nativeValue) {
    if (nativeValue == null) {
      return new MCValueObject(null);
    }
    return MCValueFactory.createMIValueOfNativeObject(nativeValue);
  }
  
  /** @return the value of a call as the interpreter represents it */
  public static MCValue toValue(final Method method, final Object nativeValue) {
    return method.getReturnType() == void.class ? MCValueVoid.INSTANCE : toValue(nativeValue);
  }
  
  public static boolean asBoolean(final Object value, final String attribute) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    throw wrongValue(value, attribute, "a boolean");
  }
  
  public static Number asNumber(final Object value, final String attribute) {
    if (value instanceof Number number) {
      return number;
    }
    if (value instanceof Character character) {
      return (int) character;
    }
    throw wrongValue(value, attribute, "a number");
  }
  
  protected static IllegalStateException wrongValue(final Object value, final String attribute,
      final String expected) {
    return new IllegalStateException("The attribute '" + attribute + "' should be " + expected
        + " but " + (value == null ? "has no value." : "is '" + value + "'."));
  }
  
  /** Generated domain classes return optional attributes as {@link Optional}. */
  protected static Object unwrap(final Object value) {
    return value instanceof Optional<?> optional ? optional.orElse(null) : value;
  }
  
  /* ======================================================================
   * Reflection
   * ====================================================================== */
  
  /**
   * Finds a public method that can be called with the arguments. Objects are often instances of
   * classes that are not public themselves (e.g., the lists of {@code List.of}); such a method is
   * looked up in the public super types, where it may be called.
   */
  protected static Optional<Method> findMethod(final Class<?> type, final String name,
      final Object[] arguments) {
    for (Class<?> candidateType : publicTypesOf(type)) {
      for (Method method : candidateType.getMethods()) {
        if (method.getName().equals(name) && !Modifier.isStatic(method.getModifiers()) && Modifier
            .isPublic(method.getDeclaringClass().getModifiers()) && accepts(method, arguments)) {
          return Optional.of(method);
        }
      }
    }
    return Optional.empty();
  }
  
  /** @return the type and all its super types that are public, most specific first */
  protected static Set<Class<?>> publicTypesOf(final Class<?> type) {
    final Set<Class<?>> result = new LinkedHashSet<>();
    final Deque<Class<?>> open = new ArrayDeque<>();
    open.add(type);
    while (!open.isEmpty()) {
      final Class<?> current = open.poll();
      if (Modifier.isPublic(current.getModifiers())) {
        result.add(current);
      }
      if (current.getSuperclass() != null) {
        open.add(current.getSuperclass());
      }
      open.addAll(List.of(current.getInterfaces()));
    }
    return result;
  }
  
  protected static boolean accepts(final Method method, final Object[] arguments) {
    final Class<?>[] parameters = method.getParameterTypes();
    if (parameters.length != arguments.length) {
      return false;
    }
    for (int i = 0; i < parameters.length; i++) {
      if (!accepts(parameters[i], arguments[i])) {
        return false;
      }
    }
    return true;
  }
  
  protected static boolean accepts(final Class<?> parameter, final Object argument) {
    if (argument == null) {
      return !parameter.isPrimitive();
    }
    if (parameter.isInstance(argument)) {
      return true;
    }
    if (parameter == boolean.class) {
      return argument instanceof Boolean;
    }
    if (parameter == char.class) {
      return argument instanceof Character;
    }
    // the interpreter calculates with int and double; Java widens and narrows them as needed
    return parameter.isPrimitive() && argument instanceof Number;
  }
  
  protected static Object call(final Method method, final Object target, final Object[] arguments) {
    final Class<?>[] parameters = method.getParameterTypes();
    final Object[] adapted = new Object[arguments.length];
    for (int i = 0; i < arguments.length; i++) {
      adapted[i] = adapt(arguments[i], parameters[i]);
    }
    try {
      return method.invoke(target, adapted);
    }
    catch (InvocationTargetException e) {
      throw new IllegalStateException("The call of '" + method.getName() + "' failed: " + e
          .getCause(), e.getCause());
    }
    catch (IllegalAccessException | IllegalArgumentException e) {
      throw new IllegalStateException("The method '" + method.getName() + "' cannot be called: " + e
          .getMessage(), e);
    }
  }
  
  protected static Object adapt(final Object argument, final Class<?> parameter) {
    if (!(argument instanceof Number number) || !parameter.isPrimitive()) {
      return argument;
    }
    if (parameter == int.class) {
      return number.intValue();
    }
    if (parameter == long.class) {
      return number.longValue();
    }
    if (parameter == double.class) {
      return number.doubleValue();
    }
    if (parameter == float.class) {
      return number.floatValue();
    }
    if (parameter == short.class) {
      return number.shortValue();
    }
    if (parameter == byte.class) {
      return number.byteValue();
    }
    return argument;
  }
  
}
