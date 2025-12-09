[![JetBrains Research](https://jb.gg/badges/research.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)

# QFARM

QFARM is a research tool for range-based rule mining, combining DFS rule-tree exploration with multi-objective genetic algorithms (Jenetics-based). It discovers high-quality rules of the form:

$$(attr1\ ∈\ [a,b])\ \land\ (attr2\ ∈\ [c,d])\ \land \...\ \implies\ (rhs\ ∈\ [lo,hi])$$

The system evaluates rules using multiple fitness metrics, evolves Pareto fronts, and outputs DOT graphs and JSON logs describing the search.

---

## How to Run

### 1. Build the shadow JAR

From the project root:

    ./gradlew shadowJar

The runnable JAR is generated at:

    build/libs/qfarm.jar

Rebuild the JAR whenever you modify the source code.

---

## 2. Running the Application

Basic syntax:

    java -jar qfarm.jar \
      --data <path/to.csv> \
      --rhs <column_name> \
      [--rhs-range <lo,hi>] \
      [--rhs-range-percentile <pLo,pHi>] \
      [optional hyperparameters...]

### Required arguments

--data  
    Path to CSV dataset.

--rhs  
    Column name of the right-hand-side attribute.

Exactly one of:  
    --rhs-range  
    --rhs-range-percentile

Examples:

    java -jar qfarm.jar \
      --data data/data_f.csv \
      --rhs BC_LDL.direct \
      --rhs-range-percentile 90,100

    java -jar qfarm.jar \
      --data data/data_f.csv \
      --rhs Glucose \
      --rhs-range 4.0, MAX

NOTE (zsh): Only quote bracket or no bracket expressions:

    --rhs-range-percentile 90,100
    --rhs-range-percentile "[90,100]"

Both ranges accept these formats:
    lo,hi
    lo..hi
    MIN,MAX
    MIN,6.0
    4.0,MAX

---

## Optional Hyperparameters

All hyperparameters can be overridden through CLI flags.
Anything not provided falls back to defaults in `HyperParameters`.

### Rule constraints

--min-support (default: 100)  
--max-support (default: 5000)  
--max-depth (default: 2)  
--max-children (default: 1)  
--max-first-children (default: 1)

### Evolution parameters

--evo-first-pop (default: 100)  
--evo-first-gen (default: 100)  
--evo-next-pop (default: 500)  
--evo-next-gen (default: 200)  
--evo-range-pop (default: 200)  
--evo-range-gen (default: 500)

### Mutation parameters

--prob-mutation (default: 1.0)  
--std-mutation (default: 0.15)

### Thresholds

--improvement-threshold (default: 10.0)

---

## Full Example

    java -jar qfarm.jar \
      --data data/data_f.csv \
      --rhs BC_LDL.direct \
      --rhs-range 4.0, MAX \
      --max-depth 3 \
      --max-children 2 \
      --max-first-children 1 \
      --evo-first-pop 120 \
      --evo-first-gen 80 \
      --evo-next-pop 300 \
      --evo-next-gen 150 \
      --evo-range-pop 200 \
      --evo-range-gen 400 \
      --prob-mutation 0.8 \
      --std-mutation 0.2 \
      --min-support 80 \
      --max-support 6000 \
      --improvement-threshold 12.0

---

## Output Files

After execution, QFARM produces:

tree.dot  
    GraphViz DOT file describing the discovered rule tree.

step_log.json  
    NDJSON step-by-step evolution log.

stdout  
    Contains printed rules, evolutionary progress, and runtime information.

You can render `tree.dot` using GraphViz:

    dot -Tsvg tree.dot -o tree.svg

---

## Developer Mode

To run without rebuilding the JAR each time, add this to `build.gradle.kts`:

    application {
        mainClass = "MainKt"
    }

Run with:

    ./gradlew run --args="--data data.csv --rhs LDL --rhs-range 4.0..7.0"

---

## Repository Structure (high-level)

...
---

## Contributing

1. Fork this repository  
2. Create a feature branch:

       git checkout -b feature/my-feature

3. Commit your work  
4. Push your branch:

       git push origin feature/my-feature

5. Open a pull request

---

## License

...

