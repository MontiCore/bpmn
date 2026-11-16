<!-- (c) https://github.com/MontiCore/monticore -->

[monticore-website]: http://www.monticore.de
[KMR24]: https://www.se-rwth.de/publications/Towards-Reference-Models-with-Conformance-Relations-for-Structure.pdf
[KRS+24]: https://www.se-rwth.de/publications/Towards-a-Semantically-Useful-Definition-of-Conformance-with-a-Reference-Model.pdf
[BPMN-spec]: https://www.omg.org/spec/BPMN

# Business Process Model and Notation (BPMN)

The Business Process Model and Notation (BPMN) is a standardized language
for modeling business processes and workflows.
This project implements the Workflow DSL, a textual notation for process models
based on the [BPMN 2.0.2 language specification][BPMN-spec] and developed with
the [MontiCore language workbench][monticore-website].
The project also provides comprehensive tooling for working with the language.

A detailed documentation for **language engineers** using or extending the
Workflow DSL is located
**[here](WorkflowDSL/src/main/grammars/de/monticore/bpmn/Workflow.md)**.

## Build the Project

* Install Gradle 8.14
* Install Java 21
* Build with command `gradle build`

> **Note:** No Gradle wrapper (`gradlew`) is included in this repository.
> Gradle must be installed manually and available on the system `PATH`,
> or invoked via its full installation path.

## BPMN Tool

After building the project, a CLI tool can be found
[here](WorkflowCLI/target/libs/BPMN.jar).

### Actions and Parameters of the Tool

The tool provides quite a number of executable actions and configurable
parameters.
The possible options are:

| Option                     | Explanation                                                                                                                |
|----------------------------|----------------------------------------------------------------------------------------------------------------------------|
| `-h,--help`                | Prints this help dialog.                                                                                                   |
| `-i,--input <file>`        | Reads the source file (mandatory) and parses the contents.                                                                 |
| `-path <dirlist>`          | Sets the artifact path for imported symbols, space separated (default is: `.`).                                            |
| `-pp,--prettyprint <file>` | Prints the AST to stdout or the specified file (optional).                                                                 |
| `-s,--symboltable <file>`  | Serializes the symbol table of the given artifact.                                                                         |
| `-v,--version`             | Prints version information.                                                                                                |
| `-ref,--reference <file>`  | Parses the file as a reference process model and checks if the input process model specified by `-i` is conform to it.    |
| `-m,--map <string>`        | Specify the names of stereotypes that are used as incarnation mappings in the concrete model. Default: `incarnates`        |
| `-x,--execute <script>`    | Executes the input process model with the workflow engine and prints what happens. The script (optional) drives the instance. |
| `-var,--variable <n=v>`    | An initial value of a data object for the execution, e.g., `-var amount=150`. May be given several times.                 |


### Small Example Workflow Model

The following example model [`PaperAuthoring`](doc/PaperAuthoring.wfm)
illustrates the textual syntax of the Workflow DSL:

```
process PaperAuthoring {

  start event Start;
  end event Done;

  task Introduction;
  task Main;
  task Conclusion;
  task Draft;
  task Research;
  task Review;

  merge xor StartIteration;
  split xor EndIteration;
  split and SplitWork;
  merge and MergeWork;


  Start -> Research -> Draft -> StartIteration -> SplitWork -> Introduction -> MergeWork;
                                                 SplitWork -> Main -> MergeWork;
                                                 SplitWork -> Conclusion -> MergeWork -> Review -> EndIteration -> Done;
               EndIteration -> StartIteration;


}
```

This example model describes the process of writing a scientific paper:
- The process has one start event `Start` and one end event `Done`
- It defines the tasks `Introduction`, `Main`, `Conclusion`, `Draft`,
  `Research`, and `Review`
- The workflow begins with the task `Research` followed by `Draft`
- Then comes the `split and` gateway after which the tasks `Introduction`,
  `Main`, and `Conclusion` are executed in parallel
- These three tasks are followed by the `merge and` gateway
- Next is the task `Review`
- Finally, a `split xor` gateway allows choosing between redoing the tasks
  `Introduction`, `Main`, and `Conclusion` or ending the process


### Step 1: Parsing and printing the model

You can parse the example model [`PaperAuthoring`](doc/PaperAuthoring.wfm)
and then print it to the console using the following command:

```shell
java -jar WorkflowCLI/target/libs/BPMN.jar -i doc/PaperAuthoring.wfm -pp
```

### Step 2: Storing the symbol table

The symbols defined in the example model
[`PaperAuthoring`](doc/PaperAuthoring.wfm) can be serialized and stored in a
`.wfsym` file using the following command:

```shell
java -jar WorkflowCLI/target/libs/BPMN.jar -i doc/PaperAuthoring.wfm -s doc/PaperAuthoring.wfsym
```

### Step 3: Conformance Checking to Reference Models

The example model [`PaperAuthoring`](doc/PaperAuthoring.wfm) can be viewed
as a reference model for writing scientific publications [[KRS+24]].
We can check whether a concrete process model conforms to it using the
option `-ref`.

The process model [`Thesis`](doc/Thesis.wfm) is a conforming model.
The check can be executed as follows:

```shell
java -jar WorkflowCLI/target/libs/BPMN.jar -i doc/Thesis.wfm -ref doc/PaperAuthoring.wfm
```

The process model [`AntiPatternMerge`](doc/AntiPatternMerge.wfm) is,
however, not conforming, as it closes parallel executed branches of tasks
with a `merge xor` gateway.
When executing the conformance check, the algorithm finds a sequence of
tasks via backtracking that are not permitted by
[`PaperAuthoring`](doc/PaperAuthoring.wfm):

```shell
java -jar WorkflowCLI/target/libs/BPMN.jar -i doc/AntiPatternMerge.wfm -ref doc/PaperAuthoring.wfm
```

### Step 4: Loading symbols from a class diagram

More complex workflow models, such as the
[`OrderToDeliveryWorkflow`](doc/de/monticore/bpmn/examples/OrderToDeliveryWorkflow.wfm)
described
**[here](WorkflowDSL/src/main/grammars/de/monticore/bpmn/Workflow.md)**,
may import symbols from class diagrams in order to utilize custom data types.
To load the serialized symbol table of the class diagram
[`OrderToDelivery`](doc/de/monticore/bpmn/cds/OrderToDelivery.cd),
the artifact path has to be set to `doc/` using the option `-path`:

```shell
java -jar WorkflowCLI/target/libs/BPMN.jar -i doc/de/monticore/bpmn/examples/OrderToDeliveryWorkflow.wfm -path doc/ -pp
```

### Step 5: Executing a model

The tool can execute a process model with the
[Workflow Engine](WorkflowEngine/src/main/java/de/monticore/bpmn/engine/WorkflowEngine.md).
It deploys the model, starts one instance and prints every step. Without a
script the instance is driven automatically:

```shell
java -jar WorkflowCLI/target/libs/BPMN.jar -i doc/PaperAuthoring.wfm -x
```

A script drives the instance step by step, lets time pass for timers and states
what is expected. The folder [`WorkflowEngine/examples`](WorkflowEngine/examples/README.md)
contains models with scripts, e.g., an approval with a reminder and a timeout:

```shell
java -jar WorkflowCLI/target/libs/BPMN.jar -i WorkflowEngine/examples/Approval.wfm -x WorkflowEngine/examples/Approval.script
```

The `OrderToDeliveryWorkflow` from above can be executed as well. The objects of
its data objects, whose types come from the class diagram, are given as plain
data in the script:

```shell
java -jar WorkflowCLI/target/libs/BPMN.jar -i WorkflowEngine/examples/OrderToDeliveryWorkflow.wfm -path WorkflowEngine/examples -x WorkflowEngine/examples/OrderToDeliveryWorkflow.script
```
