/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.engine.state;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Reads and writes a {@link PersistentProcessState} as JSON.
 *
 * <p>This is the format for the "current state" column of the process instance table. Which format
 * a store uses is its own decision; JSON is provided because it keeps the state readable and does
 * not need a schema per process.
 */
public class ProcessStateJson {
  
  protected static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  
  public static String toJson(final PersistentProcessState state) {
    return GSON.toJson(state);
  }
  
  public static PersistentProcessState fromJson(final String json) {
    return GSON.fromJson(json, PersistentProcessState.class);
  }
  
}
