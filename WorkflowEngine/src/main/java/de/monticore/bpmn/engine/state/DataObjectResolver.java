/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.state;

import java.util.Optional;

/**
 * Connects the data objects of a process with the objects of the application.
 *
 * <p>The engine stores the state of a process instance, not the data the process works on. An
 * object that the application keeps itself, e.g., an entity in its database, is therefore not
 * copied into the stored state. The engine asks this resolver for a reference instead, stores the
 * reference and has the object looked up again before every step. The process then always sees the
 * current object, and the application remains the only owner of its data.
 *
 * <p>Values the resolver does not know are stored by the engine itself if they are simple values or
 * plain data, cf. {@link PersistentValue}.
 */
public interface DataObjectResolver {
  
  /**
   * @param dataObject the name of the data object in the process model
   * @param value the object the data object holds
   * @return the reference under which {@link #resolve} finds the object again, empty if the object
   * is not one the application keeps
   */
  Optional<String> toReference(String dataObject, Object value);
  
  /**
   * @param dataObject the name of the data object in the process model
   * @param reference a reference that {@link #toReference} returned
   * @return the object the reference stands for
   */
  Object resolve(String dataObject, String reference);
  
}
