<!-- (c) https://github.com/MontiCore/monticore -->

# Examples for the Workflow Engine

Each example is a process model (`.wfm`) with one or more scripts (`.script`).
A script drives one instance of the model step by step and states what it
expects, so it documents the behavior and checks it at the same time. The test
[ExamplesTest](../src/test/java/de/monticore/bpmn/engine/ExamplesTest.java)
runs all of them with every build.

## Running an example

Build the command line tool once, in the root folder of the project:

```
gradle :WorkflowCLI:shadowJar
```

Then execute a model with a script:

```
java -jar WorkflowCLI/target/libs/BPMN.jar -i WorkflowEngine/examples/Order.wfm -x WorkflowEngine/examples/Order.script
```

Without a script the instance is driven automatically: the first open activity
is completed; if there is none, the next timer elapses; if there is none
either, the first open event is triggered. Initial values are given with `-var`:

```
java -jar WorkflowCLI/target/libs/BPMN.jar -i WorkflowEngine/examples/Order.wfm -x -var amount=20
```

The commands of a script can also be typed: with `-` as the script the tool
reads one command per line from the standard input. `help` lists the commands,
`exit` ends the run:

```
java -jar WorkflowCLI/target/libs/BPMN.jar -i WorkflowEngine/examples/Order.wfm -x -
```

The output lists what happens in every step and what the instance waits for
afterwards:

```
Deployed process 'Order' (version 1) from Order.wfm
> set amount = 150
> start
Started an instance with {amount=150}
  instance started
  event Received occurred
  activity Check started
  open: Check
> complete Check
  activity Check ended
  activity Approve started
  open: Approve
```

## The examples

| Model | Shows | Scripts |
|---|---|---|
| [Order](Order.wfm) | a decision that depends on data, default flow, values that come with a task | `Order`, `Order.default` |
| [Shipment](Shipment.wfm) | parallel split and merge, inclusive split and merge | `Shipment` |
| [Approval](Approval.wfm) | boundary timers: a reminder that does not interrupt, a timeout that does | `Approval`, `Approval.intime` |
| [Offer](Offer.wfm) | event-based gateway: a message races a timer | `Offer`, `Offer.expired` |
| [Validation](Validation.wfm) | embedded sub-process, error end event and error boundary event, multi-instance activity | `Validation`, `Validation.invalid` |
| [MonthlyTimesheet](MonthlyTimesheet.wfm) | lanes, loops back, a deadline reminder, a message that withdraws a review | `MonthlyTimesheet`, `MonthlyTimesheet.reminder`, `MonthlyTimesheet.absence` |
| [OrderToDeliveryWorkflow](OrderToDeliveryWorkflow.wfm) | the example of the project README: data objects with types of a class diagram, conditions over their attributes | `OrderToDeliveryWorkflow`, `OrderToDeliveryWorkflow.unavailable` |
| [Deadlock](Deadlock.wfm) | a model that is not sound: the engine detects that the instance is stuck | `Deadlock` |

`OrderToDeliveryWorkflow` imports the class diagram
[OrderToDelivery](de/monticore/bpmn/cds/OrderToDelivery.cd). Its symbol file is
found via the option `-path`:

```
java -jar WorkflowCLI/target/libs/BPMN.jar -i WorkflowEngine/examples/OrderToDeliveryWorkflow.wfm -path WorkflowEngine/examples -x WorkflowEngine/examples/OrderToDeliveryWorkflow.script
```

## Scripts

A script has one command per line. Empty lines and lines that start with `#`
are ignored.

| Command | Effect |
|---|---|
| `set amount = 150` | gives a data object a value; before `start` it is an initial value |
| `start` | starts the instance; implicit before the first other command |
| `complete Check` | completes an activity; values may follow: `complete Check ok=true` |
| `trigger Paid` | triggers an event; values may follow |
| `signal Check` | completes the activity or triggers the event of that name; values may follow |
| `wait P1D` | lets time pass (ISO 8601 duration) and executes the timers that become due |
| `auto` | continues automatically until the instance ends |
| `expect status COMPLETED` | checks the status: `ACTIVE`, `COMPLETED`, `TERMINATED`, `FAILED` |
| `expect open Approve, Timeout` | checks what may be signalled next |
| `expect variable amount = 150` | checks the value of a data object |
| `show` | prints what the instance waits for |
| `history` | prints the audit trail |
| `help` | lists the commands |
| `exit` | ends the run; the commands after it are not read |

A value is `true`, `false`, a number, a text in double quotes, or a JSON object
or array. A JSON object stands for an object of a class of a class diagram, its
members are the attributes.

The run starts at `2026-01-01T08:00:00Z` and time only passes with `wait`, so
the output of a script is the same on every run.
