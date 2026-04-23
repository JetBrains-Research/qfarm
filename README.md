[![JetBrains Research](https://jb.gg/badges/research.svg)](https://confluence.jetbrains.com/display/ALL/JetBrains+on+GitHub)
[![license](https://img.shields.io/github/license/mashape/apistatus.svg)](https://opensource.org/licenses/MIT)
[![Build Status](http://teamcity.jetbrains.com/app/rest/builds/buildType:(id:BioLabs_QFarm)/statusIcon.svg)](http://teamcity.jetbrains.com/viewType.html?buildTypeId=BioLabs_QFarm&guest=1)

# QFARM

QFARM is a research tool for range-based rule mining, combining DFS rule-tree exploration with multi-objective genetic algorithms (Jenetics-based). It discovers high-quality rules of the form:

$$(attr1\ ∈\ [a,b])\ \land\ (attr2\ ∈\ [c,d])\ \land \...\ \implies\ (rhs\ ∈\ [lo,hi])$$

The system evaluates rules using multiple fitness metrics, evolves Pareto fronts, and outputs DOT graphs and JSON logs describing the search.

---

## How to Run

### 1. Build the Shadow JAR

From the project root:

```bash
./gradlew shadowJar
```

The runnable JAR is generated at:

```bash
build/libs/qfarm-<version>.jar
```

Rebuild the JAR whenever you modify the source code.

---

## 2. Command-Line Interface

QFARM now uses a **multi-command CLI**. The general syntax is:

```bash
java -jar qfarm.jar <command> [options]
```

Available commands:

- `search` — run the rule mining algorithm
- `validate` — run validation procedures on a dataset (initial implementation)

---

# 🔍 Search Command

Run the main rule-mining algorithm.

## Basic syntax

```bash
java -jar qfarm.jar search \
  --data <path/to.csv> \
  --rhs <column_name> \
  [--rhs-range <lo,hi>] \
  [--rhs-range-percentile <pLo,pHi>] \
  [optional hyperparameters...]
```

---

## Required arguments

`--data`  
Path to CSV dataset.

`--rhs`  
Column name of the right-hand-side attribute.

Exactly one of:

- `--rhs-range`
- `--rhs-range-percentile`

---

## Examples

```bash
java -jar qfarm.jar search \
  --data data.csv \
  --rhs y \
  --rhs-range-percentile 90,100
```

```bash
java -jar qfarm.jar search \
  --data data.csv \
  --rhs y \
  --rhs-range 4.0,MAX
```

---

## Range formats

Both `--rhs-range` and `--rhs-range-percentile` accept:

```
lo,hi
lo..hi
MIN,MAX
MIN,6.0
4.0,MAX
```

⚠️ **zsh note**: quote bracket expressions if used:

```bash
--rhs-range-percentile "[90,100]"
```

---

## Optional Hyperparameters

All hyperparameters can be overridden through CLI flags.  
Any parameter not provided falls back to defaults defined in `HyperParameters`.

---

### Rule constraints

`--min-support` (default: 100)  
Minimum number of records that must satisfy the rule. 

`--max-support` (default: 5000)  
Maximum number of records a rule can cover. 

`--max-depth` (default: 2)  
Maximum number of attributes in the antecedent (rule length). 

`--max-children` (default: 1)  
Maximum number of children per internal node in the rule tree.  

`--max-first-children` (default: 1)  
Maximum number of children for the root node.

---

### Evolution parameters

`--evo-cheap-pop`  
Population size used in the **cheap (initial) evolution phase**. 

`--evo-cheap-gen`  
Number of generations for the cheap evolution phase.  

`--evo-full-pop`  
Population size used in the **full evolution phase**.  

`--evo-full-gen`  
Number of generations for the full evolution phase. 

---

### Mutation parameters

`--prob-mutation` (default: 1.0)  
Probability of applying mutation to a gene during evolution. 

`--std-mutation` (default: 0.15)  
Standard deviation controlling mutation magnitude. 

---

### Thresholds

`--alpha-threshold`  
Statistical significance threshold (e.g., for p-value filtering). 

---

## Full Example

```bash
java -jar qfarm.jar search \
  --data data.csv \
  --rhs y \
  --rhs-range 4.0,MAX \
  --max-depth 3 \
  --max-children 2 \
  --max-first-children 10 \
  --evo-cheap-pop 100 \
  --evo-cheap-gen 100 \
  --evo-full-pop 500 \
  --evo-full-gen 500 \
  --prob-mutation 0.75 \
  --std-mutation 0.02 \
  --min-support 5 \
  --max-support 500 \
  --alpha-threshold 0.05
```

---

# ✅ Validate Command

Run validation procedures on a dataset.

## Basic syntax

```bash
java -jar qfarm.jar validate \
  --data <path/to.csv>
  --rules <path/to.jsonl>
```

---

## Arguments

`--data` (required)  
Path to dataset used for validation.

`--rules` (required)  
Path to the JSONL file containing rules generated from a previous run.

---

## Example

```bash
java -jar build/libs/qfarm-0.1.build.jar validate \
  --data data.csv \
  --rules /path/to/previous_run/log.jsonl
```

# 🧠 Notes

- The CLI is built using **Clikt**, enabling structured subcommands.
- Commands are independent and can evolve separately.
- Future versions will expand `validate` to support rule evaluation and metrics.

---
## Output Files

Both `search` and `validate` commands produce a full set of result files inside a run-specific directory:

    results/<run_name>/
    ├── validation_summary.txt      (only for validate)
    ├── final_rules_summary.txt
    ├── final_rules_table.csv
    ├── representative_rules.txt
    ├── log.jsonl
    ├── full_tree.dot
    ├── full_tree.svg
    └── front_plots/


### Description

- `validation_summary.txt`  
  **Produced only by the `validate` command.**  
  Main validation report. Includes:
  - ROC p-values  
  - KS p-values  
  - Failure reasons (`MISSING`, `KS_FAIL`, `ROC_FAIL`, `PARENT_FAIL`)  
  - Visual rule plots  
  - Comparison with previous run (for KS failures)

- `final_rules_summary.txt`  
  Summary of discovered fronts (attribute combinations).

- `final_rules_table.csv`  
  Tabular export of fronts (attribute combinations) and their metrics.

- `representative_rules.txt`  
  Selected subset of representative rules from final fronts.

- `log.jsonl`  
  NDJSON log with detailed step-by-step execution (serves as input for validation procedure).

- `full_tree.dot`  
  GraphViz representation of the rule tree.

- `full_tree.svg`  
  Rendered tree visualization (generated automatically if GraphViz is available).

- `front_plots/`  
  HTML files with Pareto front visualizations for each rule.

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

