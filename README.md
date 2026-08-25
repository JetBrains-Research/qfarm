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

- `check` — inspect a dataset and verify whether it is ready for QFARM
- `search` — run the rule mining algorithm
- `validate` — validate rules produced by a previous QFARM run

Command-specific help is available through:

```bash
java -jar qfarm.jar check --help
java -jar qfarm.jar search --help
java -jar qfarm.jar validate --help
```

---

# Check Command

The `check` command inspects a dataset without running evolution, rule-tree search, random-AUC generation, or validation.

It is recommended to run `check` before starting a search, especially when using a new dataset.

## Basic syntax

Dataset-only check:

```bash
java -jar qfarm.jar check \
  --data <path/to.csv>
```

Optional RHS check:

```
java -jar qfarm.jar check \
  --data <path/to.csv> \
  --rhs <column_name>
```

Optional RHS range check:

```
java -jar qfarm.jar check \
  --data <path/to.csv> \
  --rhs <column_name> \
  [--rhs-range <lo,hi>] \
  [--rhs-range-percentile <pLo,pHi>]
```

## Arguments

`--data` (required)  
Path to the input dataset.

Supported formats:

```text
.csv
.csv.gz
.tsv
.tsv.gz
```

`--rhs` (optional)  
Right-hand-side target column. If omitted, QFARM performs dataset-level checks only.

`--rhs-range` (optional)  
Absolute RHS range.

Examples:

```text
4.0,8.0
4.0..8.0
MIN,8.0
4.0,MAX
MIN,MAX
```

`--rhs-range-percentile` (optional)  
Percentile-based RHS range.

Examples:

```text
80,100
80..100
[80,100]
```

`--excl-cols` (optional)  
Comma-separated list of columns to exclude.

Example:

```bash
--excl-cols id,subject_id,batch
```

Only one of `--rhs-range` and `--rhs-range-percentile` may be supplied.

An RHS range can only be supplied together with `--rhs`.

## Examples

Check only the dataset:

```bash
java -jar qfarm.jar check \
  --data data.csv
```

Check the dataset and RHS column:

```bash
java -jar qfarm.jar check \
  --data data.csv \
  --rhs y
```

Check an RHS percentile range:

```bash
java -jar qfarm.jar check \
  --data data.csv \
  --rhs y \
  --rhs-range-percentile 80,100
```

Check an absolute RHS range:

```bash
java -jar qfarm.jar check \
  --data data.csv \
  --rhs y \
  --rhs-range 4.0,MAX
```

---

## What `check` reports

The command provides a comprehensive overview of the dataset and its suitability for QFARM, including:

- dataset structure and preprocessing summary
- descriptive statistics and quantiles for each numeric column
- optional RHS validation and label distribution
- detected data quality issues (e.g., missing values, constant columns, non-finite values)
- detected discrete columns
- final readiness status (`READY`, `WARNING`, or `ERROR`)


The following quantiles are reported for every numeric column in a compact table, for example:

```text
Column              q0.1     q1       q5       q10      q25      q50      q75      q90      q95      q99      q99.9
age_years           18       18       19       21       30       46       63       75       80       85       85
glucose_mg_dl       49       64       74       78       84       91       100      121      151      272      413.344
crp_mg_l            0.1      0.1      0.2      0.4      0.8      2        4.8      10.5     16.1     37.048   103
```

Long column names are shortened by preserving the beginning and end of the name and replacing the middle with `...`.

Numeric output is limited to at most five decimal places.

---

## Check status

The command ends with one of three statuses.

### READY

```text
STATUS: READY
Dataset is ready for QFARM.
```

No blocking problems were detected.

### WARNING

```text
STATUS: WARNING
Dataset is usable, but potential issues were detected.
```

Warnings may include:

- missing values
- constant columns
- unusual RHS coverage
- other non-blocking dataset properties

### ERROR

```text
STATUS: ERROR
Dataset/configuration is not ready for QFARM.
```

Errors include conditions such as:

- dataset cannot be parsed
- no usable numeric columns
- requested RHS does not exist
- invalid RHS range
- conflicting RHS range arguments
- non-finite values such as `Infinity` or `-Infinity`

The command exits with a non-zero status when an error is detected.

---

# Search Command

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

**zsh note**: quote bracket expressions if used:

```bash
--rhs-range-percentile "[90,100]"
```

---

## Optional Hyperparameters

All hyperparameters can be overridden through CLI flags.  
Any parameter not provided falls back to defaults defined in `HyperParameters`.

### Output

`--name` (default: test_run)  
Name of the current run.

---

### Dataset columns

`--excl-cols`  
Comma-separated columns to exclude from rule antecedents.

Example:

```bash
--excl-cols id,subject_id
```

---

### Rule constraints

`--min-support` (default: 1)  
Minimum number of records that must satisfy the rule. 

`--max-support` (default: 1000000)  
Maximum number of records a rule can cover. 

`--max-width` (default: 0.8)  
Maximum normalized width allowed for continuous attribute intervals.

`--max-depth` (default: 2)  
Maximum number of attributes in the antecedent (rule length). 

`--max-children` (default: 1)  
Maximum number of children per internal node in the rule tree.  

`--max-first-children` (default: 4)  
Maximum number of children for the root node.

---

### Evolution parameters

`--evo-cheap-pop` (default: 100)  
Population size used in the **cheap (initial) evolution phase**. 

`--evo-cheap-gen` (default: 100)  
Number of generations for the cheap evolution phase.  

`--evo-full-pop` (default: 500)  
Population size used in the **full evolution phase**.  

`--evo-full-gen` (default: 500)  
Number of generations for the full evolution phase. 

---

### Mutation parameters

`--prob-mutation` (default: 0.75)  
Probability of applying mutation to a gene during evolution. 

`--std-mutation` (default: 0.02)  
Standard deviation controlling mutation magnitude. 

---

### Statistical validation

`--alpha-threshold` (default: 0.05)  
Statistical significance threshold.

`--roc-comp` (default: cp)  
ROC comparison mode.

Allowed values:

```text
c   = child only
cp  = child plus parent
m   = merge (pareto front of combined)
```

Examples:

```bash
--roc-comp c
--roc-comp cp
--roc-comp m
```

`--rand-auc-cols` (default: 1000)  
Number of random columns used to generate the level-1 empirical AUC baseline.

### Dataset & run metadata

--excl-cols (default: [])  
    Comma-separated list of column names to exclude from the dataset before rule mining.

    Example:
        --excl-cols ID,Timestamp

--name (default: auto-generated)  
    Optional run name / experiment label.
    Used for logging, plots, output directories, and DOT URLs.

    Example:
        --name experiment_1

---

## Full Example

```bash
java -jar qfarm.jar search \
  --data data/friedman.csv \
  --rhs y \
  --rhs-range-percentile 80,100 \
  --name KB-friedman \
  --min-support 1 \
  --max-support 500 \
  --max-children 3 \
  --max-depth 5 \
  --max-first-children 5 \
  --alpha-threshold 0.01 \
  --evo-cheap-pop 100 \
  --evo-cheap-gen 100 \
  --evo-full-pop 500 \
  --evo-full-gen 500 \
  --max-width 0.8 \
  --prob-mutation 0.75 \
  --std-mutation 0.02 \
  --roc-comp cp \
  --rand-auc-cols 1000
```

---

# Validate Command

Validate a previously generated QFARM rule tree on another dataset.

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

`--min-support`  
Override minimum support during validation/reconstruction.

`--max-support`  
Override maximum support during validation/reconstruction.

`--spearman-threshold`  
Threshold for smoothed Spearman distance validation during reconstruction.

---

## Example

```bash
java -jar build/libs/qfarm-0.1.build.jar validate \
  --data data.csv \
  --rules /path/to/previous_run/log.jsonl \
  --min-support 5 \
  --max-support 500 \
  --spearman-threshold 0.20
```

# Notes

- The CLI is built using **Clikt** and exposes independent `check`, `search`, and `validate` subcommands.
- `check` performs dataset and optional RHS inspection without running the genetic algorithm.
- `search` performs QFARM rule discovery.
- `validate` restores rule metadata from a previous JSONL log and re-evaluates the rules on another dataset.
- Running `check` before `search` is recommended when introducing a new dataset.

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

`check` is console-only and does not create a result directory.


### Description

- `validation_summary.txt`  
  **Produced only by the `validate` command.**  
  Main validation report. Includes:
  - ROC p-values  
  - DIST values  
  - Failure reasons (`MISSING`, `DIST_FAIL`, `ROC_FAIL`, `PARENT_FAIL`)  
  - Visual rule plots  
  - Comparison with previous run (for DIST failures)

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

Run `check` with:

```bash
./gradlew run --args="check --data data.csv"
```

Run `search` with:

```bash
./gradlew run --args="search --data data.csv --rhs y --rhs-range 21.4..30.0"
```

Run `validate` with:

```bash
./gradlew run --args="validate --data data.csv --rules results/previous_run/log.jsonl"
```

---

## Contributing

1. Fork this repository  
2. Create a feature branch:

       git checkout -b feature/my-feature

3. Commit your work  
4. Push your branch:

       git push origin feature/my-feature

5. Open a pull request

