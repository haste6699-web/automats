# Human-like movement: record → train → run

This is a **hybrid** setup: the A* pathfinder decides *where* to go (your A/B
coordinates), and a small neural net trained on **your own gameplay** decides
*how* to move (key timings + mouse motion) so it looks human.

## 1. Record your gameplay (in-game)

1. Build and launch the mod.
2. Walk to where you want the bot to learn to go and press **`\`** (Backslash)
   to set a goal – either the block you're looking at, or a point ~24 blocks
   ahead if you're looking at the sky.
3. Press **`]`** (Right Bracket) to **start recording**. You'll see a `REC ●`
   message and a running sample counter.
4. Now just **walk to the goal like a normal player** – turn, jump, sprint,
   strafe around obstacles. When you reach it, set a new goal with `\` and keep
   going. Do this from many directions and over different terrain.
5. Press **`]`** again to **stop**. Data is saved to
   `.minecraft/config/bot_dataset.jsonl` (recording appends, so you can do
   several sessions).

**Aim for ~15–60 minutes** of varied walking (roughly 20k–70k samples at 20
   ticks/sec). More and more varied = more human-like.

## 2. Train the model (one command)

```bash
pip install torch numpy
python ml/train.py /path/to/.minecraft/config/bot_dataset.jsonl
```

This prints the loss per epoch and writes **`model.onnx`** next to where you run
it. Training a tiny net like this takes seconds–minutes on a CPU.

## 3. Use it in-game

Copy `model.onnx` to `.minecraft/config/bot_model.onnx`. The mod loads it on
startup; if it's present, the walker uses the model for movement and falls back
to the plain A* humanizer if the model is missing or misbehaves.

> Note: the ONNX inference wiring + the `onnxruntime` Gradle dependency is the
> next step – ping me once you've recorded a dataset and I'll switch the bot
> over to the model.

## What the model sees and outputs

- **Input (12):** direction to goal (forward/right), yaw error, distance,
  vertical delta, on-ground, obstacle ahead (foot/chest), gap ahead, left/right
  blocked, current speed. Defined in `BotFeatures.java`.
- **Output (8):** forward, back, left, right, jump, sprint (probabilities 0..1),
  then yawDelta and pitchDelta (×15° per tick). Defined in `MovementRecorder.java`.

Keeping these two files in sync with `train.py` is what makes record/train/run
consistent – don't change the order in one place only.
