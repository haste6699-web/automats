"""Train a small behavioral-cloning policy on recorded Minecraft movement.

Usage:
    pip install torch numpy
    python train.py [path/to/bot_dataset.jsonl]

The dataset is produced by the in-game MovementRecorder. Each line is:
    {"f": [12 features], "a": [fwd, back, left, right, jump, sprint, yawDelta, pitchDelta]}

Output: model_weights.json -- plain JSON weights for the pure-Java inference in
the mod (no ONNX / no native runtime needed). Copy it to:
    .minecraft/config/model_weights.json
"""
import glob
import json
import sys

import numpy as np
import torch
import torch.nn as nn

NUM_FEATURES = 12
NUM_KEYS = 6      # forward, back, left, right, jump, sprint
NUM_CONT = 2      # yawDelta, pitchDelta
NUM_ACTIONS = NUM_KEYS + NUM_CONT


def load(pattern):
    files = glob.glob(pattern)
    if not files:
        files = [pattern]
    feats, acts = [], []
    for fp in files:
        try:
            with open(fp, "r", encoding="utf-8") as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        obj = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    f = obj.get("f")
                    a = obj.get("a")
                    if not f or not a:
                        continue
                    if len(f) != NUM_FEATURES or len(a) != NUM_ACTIONS:
                        continue
                    feats.append(f)
                    acts.append(a)
        except OSError as exc:
            print("Could not read", fp, exc)
    return (np.asarray(feats, dtype=np.float32),
            np.asarray(acts, dtype=np.float32))


class Policy(nn.Module):
    def __init__(self):
        super().__init__()
        self.backbone = nn.Sequential(
            nn.Linear(NUM_FEATURES, 128), nn.ReLU(),
            nn.Linear(128, 128), nn.ReLU(),
        )
        self.head = nn.Linear(128, NUM_ACTIONS)

    def forward(self, x):
        h = self.backbone(x)
        out = self.head(h)
        keys = torch.sigmoid(out[:, :NUM_KEYS])
        cont = out[:, NUM_KEYS:]
        return torch.cat([keys, cont], dim=1)


def main():
    data = sys.argv[1] if len(sys.argv) > 1 else "bot_dataset.jsonl"
    x, y = load(data)
    print("samples:", len(x))
    if len(x) < 50:
        print("WARNING: very little data. Record more for a usable model.")
        if len(x) == 0:
            return

    xt = torch.from_numpy(x)
    yt = torch.from_numpy(y)
    model = Policy()
    opt = torch.optim.Adam(model.parameters(), lr=1e-3)
    bce = nn.BCELoss()
    mse = nn.MSELoss()

    n = len(xt)
    batch = 256
    epochs = 60
    for ep in range(epochs):
        perm = torch.randperm(n)
        total = 0.0
        for i in range(0, n, batch):
            idx = perm[i:i + batch]
            xb = xt[idx]
            yb = yt[idx]
            pred = model(xb)
            loss_keys = bce(pred[:, :NUM_KEYS], yb[:, :NUM_KEYS])
            loss_cont = mse(pred[:, NUM_KEYS:], yb[:, NUM_KEYS:])
            loss = loss_keys + loss_cont
            opt.zero_grad()
            loss.backward()
            opt.step()
            total += loss.item() * len(idx)
        if ep == 0 or (ep + 1) % 10 == 0:
            print(f"epoch {ep + 1}/{epochs}  loss {total / n:.4f}")

    model.eval()
    weights = {
        "l0_w": model.backbone[0].weight.detach().cpu().numpy().tolist(),
        "l0_b": model.backbone[0].bias.detach().cpu().numpy().tolist(),
        "l1_w": model.backbone[2].weight.detach().cpu().numpy().tolist(),
        "l1_b": model.backbone[2].bias.detach().cpu().numpy().tolist(),
        "l2_w": model.head.weight.detach().cpu().numpy().tolist(),
        "l2_b": model.head.bias.detach().cpu().numpy().tolist(),
    }
    with open("model_weights.json", "w", encoding="utf-8") as fh:
        json.dump(weights,