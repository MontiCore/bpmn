/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.interpreter;

import de.monticore.interpreter.calculations.MICalculation;
import de.monticore.interpreter.calculations.MICalculationBoolean;
import de.monticore.interpreter.calculations.MICalculationDouble;
import de.monticore.interpreter.calculations.MICalculationInt;
import de.monticore.interpreter.calculations.MICalculationValue;
import de.monticore.interpreter.frames.MIFrame;
import de.monticore.interpreter.util.NativeStorageSelector;
import de.monticore.interpreter.util.SymbolAccessHandler;
import de.monticore.interpreter.util.TypeDispatcherHotfix;
import de.monticore.interpreter.util.TypeSymbolNativityChecker;
import de.monticore.symbols.basicsymbols._symboltable.FunctionSymbol;
import de.monticore.symbols.basicsymbols._symboltable.VariableSymbol;
import de.monticore.symbols.basicsymbols.interpreter.frames.MIFrameLayoutForBasicSymbols;
import de.monticore.symboltable.ISymbol;
import de.monticore.types.check.SymTypeExpression;
import de.monticore.values.MCValue;
import de.monticore.values.MCValueFunction;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.function.Function;

/**
 * Lets expressions of a process access the attributes and methods of objects whose type is defined
 * by a model, e.g., by a class diagram.
 *
 * <p>The expression interpreter of MontiCore only accesses members of native Java types, i.e., of
 * types whose symbols were derived from Java classes. It leaves all other types to the language
 * that uses it. For the Workflow language these are the types of the {@code data} objects. Their
 * objects are provided by the embedding application at runtime and are accessed by name, cf.
 * {@link ModelObjectAccess}.
 */
public class ModelObjectAccessHandler extends SymbolAccessHandler {
  
  @Override
  public SymbolAccess getSymbolAccess(final ISymbol symbol,
      final MIFrameLayoutForBasicSymbols frameLayout, final SymTypeExpression objType,
      final MICalculationValue objCalc) {
    if (isNativeJavaMember(symbol)) {
      try {
        return super.getSymbolAccess(symbol, frameLayout, objType, objCalc);
      }
      catch (RuntimeException e) {
        // a Java class of that name exists but does not expose the member the way the type
        // check expects it, e.g., a generated class with a getter instead of a public field
      }
    }
    final String name = symbol.getName();
    if (TypeDispatcherHotfix.isFieldSymbol(symbol)) {
      return new SymbolAccess(attributeGetter((VariableSymbol) symbol, objCalc), Optional.empty());
    }
    if (TypeDispatcherHotfix.isMethodSymbol(symbol)) {
      final MICalculationValue getter = frame -> new MethodOfObject(nativeObject(objCalc.calculate(
          frame)), name);
      return new SymbolAccess(getter, Optional.empty());
    }
    throw new IllegalStateException("The member '" + name
        + "' is neither an attribute nor a method and cannot be accessed.");
  }
  
  protected boolean isNativeJavaMember(final ISymbol symbol) {
    try {
      if (TypeDispatcherHotfix.isFieldSymbol(symbol)) {
        return TypeSymbolNativityChecker.isNativeJavaVariable((VariableSymbol) symbol);
      }
      if (TypeDispatcherHotfix.isMethodSymbol(symbol)) {
        return TypeSymbolNativityChecker.isNativeJavaFunction((FunctionSymbol) symbol);
      }
    }
    catch (LinkageError e) {
      // The check looks for a Java class with the name of the model type. On a file system that
      // ignores case it can come across a class whose name only differs in case, e.g., the class
      // 'shop.Order' for the type 'Shop.Order' of the diagram 'Shop'. That is not the type.
    }
    return false;
  }
  
  /** Creates the calculation that reads an attribute, in the format the interpreter stores it. */
  protected MICalculation attributeGetter(final VariableSymbol attribute,
      final MICalculationValue objCalc) {
    final String name = attribute.getName();
    final Function<MIFrame, Object> loader = frame -> ModelObjectAccess.get(nativeObject(objCalc
        .calculate(frame)), name);
    final MICalculationBoolean booleanCalc = frame -> ModelObjectAccess.asBoolean(loader.apply(
        frame), name);
    final MICalculationInt intCalc = frame -> ModelObjectAccess.asNumber(loader.apply(frame), name)
        .intValue();
    final MICalculationDouble doubleCalc = frame -> ModelObjectAccess.asNumber(loader.apply(frame),
        name).doubleValue();
    final MICalculationValue valueCalc = frame -> ModelObjectAccess.toValue(loader.apply(frame));
    return NativeStorageSelector.<MICalculation> switchByFormat(attribute, booleanCalc, intCalc,
        doubleCalc, valueCalc);
  }
  
  protected static Object nativeObject(final MCValue value) {
    return value == null ? null : value.asNativeObject();
  }
  
  /** A method of an object, bound to that object and resolved by name when it is called. */
  protected static class MethodOfObject implements MCValueFunction {
    
    protected final Object target;
    
    protected final String name;
    
    protected MethodOfObject(final Object target, final String name) {
      this.target = target;
      this.name = name;
    }
    
    @Override
    public MCValue execute(final MCValue[] arguments) {
      final Object[] nativeArguments = new Object[arguments.length];
      for (int i = 0; i < arguments.length; i++) {
        nativeArguments[i] = nativeObject(arguments[i]);
      }
      if (target == null) {
        throw new IllegalStateException("The method '" + name
            + "' cannot be called, the object it belongs to has no value.");
      }
      final Method method = ModelObjectAccess.findMethod(target.getClass(), name, nativeArguments)
          .orElseThrow(() -> new IllegalStateException("An object of the class '" + target
              .getClass().getName() + "' has no public method '" + name + "' for "
              + arguments.length + " argument(s)."));
      return ModelObjectAccess.toValue(method, ModelObjectAccess.unwrap(ModelObjectAccess.call(
          method, target, nativeArguments)));
    }
    
    @Override
    public String printType() { return "Method(" + name + ")"; }
    
  }
  
}
