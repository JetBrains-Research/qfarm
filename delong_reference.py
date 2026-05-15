import sys
import json
import numpy as np
from MLstatkit import Delong_test

data = json.loads(sys.stdin.read())

y = np.array(data["labels"])
scores1 = np.array(data["scores1"])
scores2 = np.array(data["scores2"])

z, p_two, _, _, auc1, auc2, _ = Delong_test(
    y,
    scores1,
    scores2,
    alpha=0.95,
    return_ci=True,
    return_auc=True,
    verbose=0,
)

# Handle NaN safely
if np.isnan(z) or np.isnan(p_two):
    p_one = float("nan")
else:
    # Proper mathematical one-sided conversion
    if z > 0:
        p_one = p_two / 2.0
    else:
        p_one = 1.0 - (p_two / 2.0)

result = {
    "auc1": float(auc1),
    "auc2": float(auc2),
    "z": float(z),
    "p2": float(p_two),
    "p1": float(p_one)
}

print(json.dumps(result))
