<!-- (c) https://github.com/MontiCore/monticore -->

# Workflow Engine

<!-- Alpha-version: This is intended to become a stable part of the BPMN project. -->

The Workflow engine executes models of the
[Workflow language](../../../../../../../WorkflowDSL/src/main/grammars/de/monticore/bpmn/Workflow.mc4).
It turns the language from a documentation language into an executable one: a
deployed model can be instantiated, and every instance is driven forward by the
application that embeds the engine.

The engine does not execute the model itself, it delegates that to the
[BPMNInterpreter](../../../../../../../WorkflowDSL/src/main/java/de/monticore/bpmn/interpreter/BPMNInterpreter.java).
The interpreter knows BPMN semantics but only the state of one instance in
memory; the engine adds everything that is needed to run many instances over a
long time: deployment, versioning, durable state, scheduling and an audit trail.

## Architecture

The engine is a **library**: it runs inside the application, it is not a server.
It starts no threads and opens no connections. Everything it remembers is in
three tables, which the application provides as repositories:

| Table               | Interface                      | Content                                         |
|---------------------|--------------------------------|-------------------------------------------------|
| Process definitions | `ProcessDefinitionRepository`  | one row per deployed **version** of a model     |
| Process instances   | `ProcessInstanceRepository`    | one row per running or finished **instance**    |
| Jobs                | `JobRepository`                | one row per **timer** an instance waits for     |
| History             | `HistoryLog`                   | the audit trail, one row per step               |

The default implementations keep everything in memory, which is enough for
tests. To persist, implement the four interfaces against a database; nothing
else changes.

## How one step works

The engine holds **no state between two calls**. Every call does the same five
things:

1. load the instance from the instance table,
2. load its process definition and parse the model if it is not in memory,
3. rebuild the interpreter state from the stored state (`ProcessStateCodec`),
4. let the interpreter perform the step,
5. store the new state, write the history and update the jobs.

This is what makes the state durable: the engine can be killed at any moment
between two calls and the instances continue where they were. The
[DurableStateTest](../../../../../test/java/de/monticore/bpmn/engine/DurableStateTest.java)
demonstrates exactly this by building a second engine over the same tables.

## Durable state

The interpreter works on AST objects, which only live in one JVM. A stored state
must not contain such references, so `ProcessStateCodec` maps every AST object to
a name of the model and back. A stored state is therefore readable without the
engine:

```json
{
  "tokens": ["Pack->Join"],
  "running": ["Invoice"],
  "waiting": [],
  "iterations": {},
  "thrown": ["Ordered"],
  "terminated": false,
  "variables": {
    "amount": { "kind": "INT", "value": "150" }
  }
}
```

`tokens` are the sequence flows that carry a token (`source->target`), `running`
are the activities that were started, `waiting` are the catching events that wait
for their trigger. Which format a store uses is its own decision; `ProcessStateJson`
provides JSON because it needs no schema per process.

## Versioning

Every deployment of a process gets the next version; the id of a definition is
`<process name>:<version>`, e.g. `Order:2`. An instance stores the id of the
version it was started with, so a new deployment never changes the behavior of
instances that are already running. New instances use the latest version unless
`startVersion` names another one.

## Scheduling

An instance that waits for a timer does not occupy a thread, it leaves a `Job`
behind. The engine computes the due date from the `TimerCondition` of the model
(`TimerCalculator`) at the moment the instance starts to wait, and removes the
job as soon as the instance stops waiting. This works for events in the flow and
for boundary events of a running activity, i.e., for timeouts.

Something outside the engine has to say when time passes:

```java
// in a scheduled thread, a cron job, a message from a queue, ...
engine.executeDueJobs();
```

## Usage

```java
WorkflowEngine engine = new WorkflowEngine();
engine.deploy(Files.readString(Path.of("Order.wfm")));

ProcessInstance instance = engine.start("Order", Map.of("amount", 150));
engine.getEnabledSignals(instance.getId());            // what may happen next
instance = engine.completeTask(instance.getId(), "Check");
instance = engine.triggerEvent(instance.getId(), "PaymentReceived");
engine.executeDueJobs();

engine.getVariables(instance.getId());
engine.getHistory().findByInstanceId(instance.getId());
```

What a task actually does is not part of the Workflow language. Attach it to the
interpreter with `BPMNInterpreter#setBehavior`, or let the application do the
work and only report the result with `completeTask`.

## Not implemented yet

* sub-processes and call activities run as opaque activities; executing them
  needs a child interpreter per instance
* multi-instance loops, compensation, transactions and ad-hoc sub-processes
* data flow along the `in`/`out` sets of activities
* repeating timers (`every`, `cron`) fire once and are not rescheduled
* optimistic locking of instances; concurrent steps on the same instance are not
  detected yet
* variables of a type other than `boolean`, `int`, `double` and `String` are not
  persisted

## Further Information

* [BPMNInterpreter](../../../../../../../WorkflowDSL/src/main/java/de/monticore/bpmn/interpreter/BPMNInterpreter.java)
* [MontiCore Interpreter documentation](https://github.com/MontiCore/monticore/blob/dev/monticore-runtime/src/main/java/de/monticore/interpreter/Interpreter.md)
* [Project root: MontiCore @github](https://github.com/MontiCore/monticore)
