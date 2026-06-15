package karasu_lab.fabric_example.bot;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pure-Java inference for the behavioral-cloning policy trained by ml/train.py.
 *
 * <p>The model is a tiny MLP (12 -> 128 -> 128 -> 8). We load its weights from
 * <code>.minecraft/config/model_weights.json</code> and run the forward pass by
 * hand, so there is NO ONNX runtime / native dependency to ship inside the mod.
 *
 * <p>Output layout (length 8), matching {@link MovementRecorder}:
 * [forward, back, left, right, jump, sprint] as 0..1 probabilities, then
 * [yawDelta, pitchDelta] in the -1..1 range (multiply by 15 degrees to apply).
 */
public class BotBrain {
	private static final Gson GSON = new Gson();
	private static final String FILE_NAME = "model_weights.json";

	private static final class Weights {
		float[][] l0_w;
		float[] l0_b;
		float[][] l1_w;
		float[] l1_b;
		float[][] l2_w;
		float[] l2_b;
	}

	private Weights weights;
	private long lastModified = -1;

	public boolean isReady() {
		return weights != null;
	}

	/**
	 * Loads or hot-reloads the weights file if present/changed.
	 * @return true if a usable model is loaded.
	 */
	public boolean ensureLoaded() {
		try {
			Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
			if (!Files.exists(path)) {
				weights = null;
				lastModified = -1;
				return false;
			}
			long mod = Files.getLastModifiedTime(path).toMillis();
			if (weights != null && mod == lastModified) {
				return true;
			}
			String json = Files.readString(path);
			Weights parsed = GSON.fromJson(json, Weights.class);
			if (parsed == null || parsed.l0_w == null || parsed.l1_w == null || parsed.l2_w == null) {
				weights = null;
				return false;
			}
			weights = parsed;
			lastModified = mod;
			return true;
		} catch (Exception e) {
			weights = null;
			return false;
		}
	}

	/**
	 * Runs the policy on a 12-feature vector. Returns 8 outputs, or null if no
	 * model is loaded.
	 */
	public float[] infer(float[] f) {
		Weights w = weights;
		if (w == null || f == null) {
			return null;
		}
		float[] h0 = relu(linear(w.l0_w, w.l0_b, f));
		float[] h1 = relu(linear(w.l1_w, w.l1_b, h0));
		float[] out = linear(w.l2_w, w.l2_b, h1);
		float[] result = new float[out.length];
		for (int i = 0; i < out.length; i++) {
			result[i] = i < 6 ? sigmoid(out[i]) : out[i];
		}
		return result;
	}

	private static float[] linear(float[][] w, float[] b, float[] x) {
		int outDim = w.length;
		float[] y = new float[outDim];
		for (int o = 0; o < outDim; o++) {
			float[] row = w[o];
			float sum = b != null && o < b.length ? b[o] : 0.0F;
			int n = Math.min(row.length, x.length);
			for (int i = 0; i < n; i++) {
				sum += row[i] * x[i];
			}
			y[o] = sum;
		}
		return y;
	}

	private static float[] relu(float[] x) {
		for (int i = 0; i < x.length; i++) {
			if (x[i] < 0.0F) {
				x[i] = 0.0F;
			}
		}
		return x;
	}

	private static float sigmoid(float v) {
		return (float) (1.0 / (1.0 + Math.exp(-v)));
	}
}
