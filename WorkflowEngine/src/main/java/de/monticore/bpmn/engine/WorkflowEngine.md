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

## Try it

The command line tool executes a model and prints what happens:

```
gradle :WorkflowCLI:shadowJar
java -jar WorkflowCLI/target/libs/BPMN.jar -i WorkflowEngine/examples/Order.wfm -x -var amount=150
java -jar WorkflowCLI/target/libs/BPMN.jar -i WorkflowEngine/examples/Approval.wfm -x WorkflowEngine/examples/Approval.script
```

Without a script the instance is driven automatically. A script drives it step
by step and states what it expects; with `-x -` its commands are typed. The folder
[examples](../../../../../../examples/README.md) contains models with scripts
for the constructs the engine supports.

## Architecture

The engine is a **library**: it runs inside the application, it is not a server.
It starts no threads and opens no connections. Everything it remembers is in
four tables, which the application provides as repositories:

| Table               | Interface                      | Content                                         |
|---------------------|--------------------------------|-------------------------------------------------|
| Process definitions | `ProcessDefinitionRepository`  | one row per deployed **version** of a model     |
| Process instances   | `ProcessInstanceRepository`    | one row per running or finished **instance**    |
| Jobs                | `JobRepository`                | one row per **timer** an instance waits for     |
| History             | `HistoryLog`                   | the audit trail, one row per occurrence         |

The default implementations keep everything in memory, which is enough for
tests. To persist, implement the four interfaces against a database; nothing
else changes.

The engine may be called from several threads. It performs one call at a time,
because MontiCore keeps its symbol table and its log in static fields.

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

A step is **atomic**. If it cannot be performed, nothing of it is stored, the
engine throws a `ProcessExecutionException` and the instance is exactly where it
was. Typical causes are a condition that reads a data object without a value, a
gateway without an enabled outgoing flow, and a signal the instance does not
wait for. The caller can remove the cause and try again. The failed attempt is
recorded in the history. An error never ends the JVM, although the log of
MontiCore does that by default.

## Deployment and versioning

`deploy` runs the front end of the language: parser, symbol table and context
conditions. A model with errors is not deployed, the engine throws a
`ProcessDeploymentException` that lists them.

Every deployment of a process gets the next version; the id of a definition is
`<process name>:<version>`, e.g. `Order:2`. An instance stores the id of the
version it was started with, so a new deployment never changes the behavior of
instances that are already running. New instances use the latest version unless
`startVersion` names another one.

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
for their trigger. An element inside a sub-process is stored under its qualified
name, e.g. `Shipping.Pack`. Which format a store uses is its own decision;
`ProcessStateJson` provides JSON because it needs no schema per process.

## Data

The `data` objects of a process are its variables. They get their values when
the instance is started, when a task is completed, when an event is triggered or
with `setVariables`:

```java
engine.start("Order", Map.of("amount", 150));
engine.completeTask(id, "Review", Map.of("approved", true));
```

A value has to fit the type of the data object. A data object of the type
`boolean`, `int`, `double` or `String` holds a simple value. A data object whose
type is a class of a class diagram holds an object. The conditions of the
process read its attributes, e.g. `[order.totalCost > 1000.0]`. Such an object
can be given in two ways:

* as **plain data**: a map whose keys are the attributes. The engine stores it
  with the instance as JSON.
* as an **object of the application**, e.g. an entity of its database. The
  application registers a `DataObjectResolver`; the engine stores only the
  reference the resolver provides and has the object looked up again before
  every step. The process then always reads the current object, and the
  application remains the owner of its data. Attributes are read via getters
  (`getX()`, `isX()`, `x()`) or public fields.

A condition that reads a data object without a value fails the step; the engine
does not assume a default value.

## Scheduling

An instance that waits for a timer does not occupy a thread, it leaves a `Job`
behind. The engine computes the due date from the `TimerCondition` of the model
(`TimerCalculator`) at the moment the timer starts to run: when a catching event
gets its token and when the activity of a boundary event starts. The job is
removed as soon as the event is not armed anymore.

Something outside the engine has to say when time passes:

```java
// in a scheduled thread, a cron job, a message from a queue, ...
engine.executeDueJobs();
```

A job is executed once. A timer that occurs repeatedly (`every`, `cron`) gets a
new job for every occurrence. A job whose step fails is kept and tried again
with the next calls; after three failed attempts it stays in the job table with
the reason and is not tried again.

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

`getOpenActivities` lists the activities that wait for `completeTask`,
`getOpenEvents` the events that may be triggered, including the boundary events
of running activities.

An instance ends in one of three ways: `COMPLETED` when all tokens reached the
end of their path, `TERMINATED` by a terminate event, and `FAILED` when it
cannot continue: an error end event was not caught, or the instance is stuck in
a deadlock, e.g., a parallel merge waits for a branch that was never activated.
`ProcessInstance#getFailure` tells why.

What a task actually does is not part of the Workflow language. All tasks,
whatever their type, are started by the engine and completed by the application
with `completeTask`. Messages and signals are not correlated by the engine
either: a throwing event passes its token on, a catching event waits for
`triggerEvent`.

## What is executed

| Construct | Execution |
|---|---|
| Tasks of all types | started by the engine, completed by the application |
| Exclusive, parallel and inclusive gateways, default flows | as in BPMN; the inclusive merge waits for all branches that can still arrive |
| Conditional flows of activities and events | every flow whose condition holds, the default flow otherwise |
| Start events | without a trigger they fire at once; if all have a trigger, the first that occurs starts the instance |
| Intermediate and boundary events with message, signal and timer triggers | wait for `triggerEvent` or their timer; boundary events interrupt or not as modelled |
| Exclusive event-based gateway | the first event or receive task wins, the others are cancelled |
| Terminate end event | ends the instance, inside a sub-process only that sub-process |
| Error end event and error boundary event | the error interrupts the sub-process and leaves it via the boundary event for that error; an error nobody catches fails the instance |
| Embedded sub-processes | the inner flow is executed; the sub-process completes on its own |
| Standard loops (`while`, `until`, `max`) | as in BPMN |
| Multi-instance activities (`count`) | one completion per instance; parallel instances are completed one after the other |
| Lanes | group elements, no effect on the execution |

## Not implemented yet

`ProcessDefinition#getLimitations` lists the constructs of a deployed model that
are executed in a simplified way. These are:

* call activities and sub-processes without a body run as opaque activities
  that the application completes; the called process is not instantiated
* transactions and ad-hoc sub-processes run like embedded sub-processes;
  cancellation, compensation, ordering and completion conditions are not
  executed
* conditional events (`when`) are not evaluated, the application has to trigger
  them; the guard of a complex gateway is not evaluated
* events with several triggers occur with the first trigger

Further gaps:

* data flow along the `in`/`out` sets of activities, and the items of a
  multi-instance activity over a collection
* optimistic locking of instances in a store that several engines share
* migration of running instances to another version

## Further Information

* [BPMNInterpreter](../../../../../../../WorkflowDSL/src/main/java/de/monticore/bpmn/interpreter/BPMNInterpreter.java)
* [Examples](../../../../../../examples/README.md)
* [MontiCore Interpreter documentation](https://github.com/MontiCore/monticore/blob/dev/monticore-runtime/src/main/java/de/monticore/interpreter/Interpreter.md)
* [Project root: MontiCore @github](https://github.com/MontiCore/monticore)
