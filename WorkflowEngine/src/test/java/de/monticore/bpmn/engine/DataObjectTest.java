/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.monticore.bpmn.engine.instance.ProcessInstance;
import de.monticore.bpmn.engine.state.DataObjectResolver;
import de.monticore.bpmn.engine.state.PersistentValue;
import de.monticore.bpmn.engine.state.ProcessStateJson;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Data objects whose type is defined by a class diagram. */
public class DataObjectTest extends AbstractEngineTest {
  
  /** The class diagram OrderToDelivery is available as a symbol file next to the examples. */
  protected static final Path SYMBOLS = Path.of("examples");
  
  protected static final String STOCK =
      """
          import de.monticore.bpmn.cds.OrderToDelivery.*;
          
          process Stock {
            data order : Order;
            data checker : InventoryAvailabilityChecker;
          
            start event Start;
            task Count;
            split xor Available;
            task Ship;
            task Reorder;
            task Insure;
            merge xor Join;
            end event Done;
          
            Start -> Count -> Available;
            Available -> [checker.allProductsAvailable && order.numberOfOrderedProducts > 0] Ship -> Join;
            Available -> [order.checker.allProductsAvailable && order.totalCost > 1000.0] Insure
              -> Join;
            Available -> [_] Reorder -> Join;
            Join -> Done;
          }
          """;
  
  protected static Map<String, Object> order(final int products, final double cost,
      final boolean available) {
    final Map<String, Object> order = new LinkedHashMap<>();
    order.put("orderID", "O-1");
    order.put("numberOfOrderedProducts", products);
    order.put("totalCost", cost);
    order.put("checker", Map.of("allProductsAvailable", available));
    return order;
  }
  
  @Test
  public void testConditionsReadAttributesOfObjectsGivenAsPlainData() {
    final WorkflowEngine engine = new WorkflowEngine().withSymbolPath(SYMBOLS);
    engine.deploy(STOCK);
    
    ProcessInstance instance = engine.start("Stock", Map.of("order", order(2, 50.0, false),
        "checker", Map.of("allProductsAvailable", true)));
    instance = engine.completeTask(instance.getId(), "Count");
    assertEquals(List.of("Ship"), engine.getEnabledSignals(instance.getId()));
    
    // the object changes with the task: nothing is ordered anymore, nested attribute and double
    instance = engine.start("Stock", Map.of("order", order(2, 50.0, false), "checker", Map.of(
        "allProductsAvailable", true)));
    instance = engine.completeTask(instance.getId(), "Count", Map.of("order", order(0, 5000.0,
        true)));
    assertEquals(List.of("Insure"), engine.getEnabledSignals(instance.getId()));
    
    instance = engine.start("Stock", Map.of("order", order(1, 1.0, false), "checker", Map.of(
        "allProductsAvailable", false)));
    instance = engine.completeTask(instance.getId(), "Count");
    assertEquals(List.of("Reorder"), engine.getEnabledSignals(instance.getId()));
  }
  
  @Test
  public void testPlainDataIsStoredWithTheInstance() {
    final WorkflowEngine engine = new WorkflowEngine().withSymbolPath(SYMBOLS);
    engine.deploy(STOCK);
    final ProcessInstance instance = engine.start("Stock", Map.of("order", order(2, 50.0, true),
        "checker", Map.of("allProductsAvailable", true)));
    
    final PersistentValue stored = instance.getState().getVariables().get("order");
    assertEquals(PersistentValue.Kind.JSON, stored.getKind());
    assertTrue(stored.getValue().contains("\"numberOfOrderedProducts\":2"));
    // the stored state as a whole is JSON and can be read back
    final String json = ProcessStateJson.toJson(instance.getState());
    assertEquals(stored.getValue(), ProcessStateJson.fromJson(json).getVariables().get("order")
        .getValue());
    
    final Object order = engine.getVariables(instance.getId()).get("order");
    assertEquals(2L, ((Map<?, ?>) order).get("numberOfOrderedProducts"));
  }
  
  @Test
  public void testAMissingAttributeFailsTheStep() {
    final WorkflowEngine engine = new WorkflowEngine().withSymbolPath(SYMBOLS);
    engine.deploy(STOCK);
    final ProcessInstance instance = engine.start("Stock", Map.of("order", order(2, 50.0, true),
        "checker", Map.of("somethingElse", true)));
    
    final ProcessExecutionException e = assertThrows(ProcessExecutionException.class, () -> engine
        .completeTask(instance.getId(), "Count"));
    assertTrue(e.getMessage().contains("allProductsAvailable"));
    assertEquals(List.of("Count"), engine.getEnabledSignals(instance.getId()));
  }
  
  /** A class of the application; the engine does not know it and reads it via its getters. */
  public static class Checker {
    
    protected final String id;
    
    protected boolean available;
    
    public Checker(final String id, final boolean available) {
      this.id = id;
      this.available = available;
    }
    
    public String getId() { return id; }
    
    public boolean isAllProductsAvailable() { return available; }
    
    public Optional<String> getComment() { return Optional.empty(); }
    
  }
  
  @Test
  public void testObjectsOfTheApplicationAreStoredAsReferencesAndReadLive() {
    final Map<String, Checker> database = new LinkedHashMap<>();
    database.put("c-1", new Checker("c-1", false));
    final DataObjectResolver resolver = new DataObjectResolver() {
      
      @Override
      public Optional<String> toReference(final String dataObject, final Object value) {
        return value instanceof Checker checker ? Optional.of(checker.getId()) : Optional.empty();
      }
      
      @Override
      public Object resolve(final String dataObject, final String reference) {
        return database.get(reference);
      }
      
    };
    final WorkflowEngine engine = new WorkflowEngine().withSymbolPath(SYMBOLS)
        .withDataObjectResolver(resolver);
    engine.deploy(STOCK);
    
    ProcessInstance instance = engine.start("Stock", Map.of("order", order(2, 50.0, false),
        "checker", database.get("c-1")));
    // the engine keeps the reference, not the object
    final PersistentValue stored = instance.getState().getVariables().get("checker");
    assertEquals(PersistentValue.Kind.REFERENCE, stored.getKind());
    assertEquals("c-1", stored.getValue());
    assertEquals(PersistentValue.Kind.JSON, instance.getState().getVariables().get("order")
        .getKind());
    
    // the application changes its object; the process sees the change in its next step
    database.put("c-1", new Checker("c-1", true));
    instance = engine.completeTask(instance.getId(), "Count");
    assertEquals(List.of("Ship"), engine.getEnabledSignals(instance.getId()));
  }
  
  @Test
  public void testAnObjectThatCannotBeStoredIsRejected() {
    final WorkflowEngine engine = new WorkflowEngine().withSymbolPath(SYMBOLS);
    engine.deploy(STOCK);
    
    // neither plain data nor known to a resolver
    final ProcessExecutionException e = assertThrows(ProcessExecutionException.class, () -> engine
        .start("Stock", Map.of("order", order(1, 1.0, true), "checker", new Checker("c-1", true))));
    assertTrue(e.getMessage().contains("0xWFE0101"));
    assertTrue(engine.getInstances().findAll().isEmpty());
  }
  
}
