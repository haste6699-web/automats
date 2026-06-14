package karasu_lab.fabric_example;

import karasu_lab.fabric_example.bot.AutoWalkerBot;
import karasu_lab.fabric_example.bot.BotConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

public class FabricExampleModClient implements ClientModInitializer {
	private static final int FIRST_TARGET_MODEL_DATA = 196;
	private static final int SECOND_TARGET_MODEL_DATA = 198;
	private static final String FIRST_TARGET_NAME = "+ 1 \u043c\u0430\u0442";
	private static final String SECOND_TARGET_NAME = "\u041d\u0410\u0416\u041c\u0418";
	private static final int MIN_CLICKS_PER_SECOND = 1;
	private static final int MAX_CLICKS_PER_SECOND = 100;
	private static final String KEY_CATEGORY = "key.categories.gui_auto_clicker";

	private KeyBinding openConfigKey;
	private KeyBinding toggleKey;
	private KeyBinding toggleBotKey;

	// Persistent settings (click speed, points, flags) survive relogs.
	private final BotConfig config = BotConfig.load();
	private final AutoWalkerBot bot = new AutoWalkerBot(config);

	private int clickCooldown;

	@Override
	public void onInitializeClient() {
		openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.gui_auto_clicker.open_config",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_RIGHT_SHIFT,
				KEY_CATEGORY
		));
		toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.gui_auto_clicker.toggle",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_RIGHT_CONTROL,
				KEY_CATEGORY
		));
		toggleBotKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.gui_auto_clicker.toggle_bot",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_RIGHT_ALT,
				KEY_CATEGORY
		));

		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void tick(MinecraftClient client) {
		while (openConfigKey.wasPressed()) {
			client.setScreen(new AutoClickerConfigScreen(client.currentScreen));
		}

		while (toggleKey.wasPressed()) {
			config.autoClickerEnabled = !config.autoClickerEnabled;
			config.save();
		}

		while (toggleBotKey.wasPressed()) {
			if (bot.isRunning()) {
				bot.stop();
			} else {
				bot.start();
			}
		}

		// The walker drives movement keys; run it before the auto clicker.
		bot.tick(client);

		tickAutoClicker(client);
	}

	private void tickAutoClicker(MinecraftClient client) {
		if (!config.autoClickerEnabled) {
			return;
		}

		if (clickCooldown > 0) {
			clickCooldown--;
			return;
		}

		if (client.player == null || client.interactionManager == null || !(client.currentScreen instanceof HandledScreen<?> screen)) {
			return;
		}

		ScreenHandler handler = screen.getScreenHandler();
		TargetSlot targetSlot = findTargetSlot(handler);
		if (targetSlot == null) {
			return;
		}

		int clickCount = targetSlot.fastClick() ? getFastClickCountPerTick() : 1;
		for (int i = 0; i < clickCount; i++) {
			client.interactionManager.clickSlot(handler.syncId, targetSlot.slot().id, 0, SlotActionType.PICKUP, client.player);
		}

		clickCooldown = targetSlot.fastClick() ? getFastClickCooldownTicks() : getClickCooldownTicks();
	}

	private int getClickCooldownTicks() {
		return Math.max(0, Math.round(20.0F / config.clicksPerSecond) - 1);
	}

	private int getFastClickCooldownTicks() {
		return config.clicksPerSecond <= 20 ? getClickCooldownTicks() : 0;
	}

	private int getFastClickCountPerTick() {
		return Math.max(1, (int)Math.ceil(config.clicksPerSecond / 20.0D));
	}

	private static TargetSlot findTargetSlot(ScreenHandler handler) {
		Slot firstTargetSlot = null;

		for (Slot slot : handler.slots) {
			ItemStack stack = slot.getStack();
			if (isSecondTargetStack(stack)) {
				return new TargetSlot(slot, true);
			}

			if (firstTargetSlot == null && isFirstTargetStack(stack)) {
				firstTargetSlot = slot;
			}
		}

		return firstTargetSlot == null ? null : new TargetSlot(firstTargetSlot, false);
	}

	private record TargetSlot(Slot slot, boolean fastClick) {
	}

	private static boolean isTargetStack(ItemStack stack) {
		return isFirstTargetStack(stack) || isSecondTargetStack(stack);
	}

	private static boolean isFirstTargetStack(ItemStack stack) {
		return hasTargetComponents(stack, FIRST_TARGET_MODEL_DATA, FIRST_TARGET_NAME);
	}

	private static boolean isSecondTargetStack(ItemStack stack) {
		return hasTargetComponents(stack, SECOND_TARGET_MODEL_DATA, SECOND_TARGET_NAME);
	}

	private static boolean hasTargetComponents(ItemStack stack, int targetModelData, String targetName) {
		if (stack.isEmpty()) {
			return false;
		}

		CustomModelDataComponent modelData = stack.get(DataComponentTypes.CUSTOM_MODEL_DATA);
		if (modelData == null) {
			return false;
		}

		Text customName = stack.get(DataComponentTypes.CUSTOM_NAME);
		String name = customName == null ? "" : customName.getString();

		return modelData.value() == targetModelData && targetName.equals(name);
	}

	private class AutoClickerConfigScreen extends Screen {
		private static final int PANEL_WIDTH = 260;
		private static final int PANEL_HEIGHT = 210;

		private final Screen parent;

		private AutoClickerConfigScreen(Screen parent) {
			super(Text.literal("GUI Auto Clicker"));
			this.parent = parent;
		}

		@Override
		protected void init() {
			int panelX = (width - PANEL_WIDTH) / 2;
			int panelY = (height - PANEL_HEIGHT) / 2;
			int buttonWidth = 220;
			int buttonX = panelX + (PANEL_WIDTH - buttonWidth) / 2;
			int halfWidth = (buttonWidth - 6) / 2;

			addDrawableChild(new SpeedSlider(buttonX, panelY + 42, buttonWidth, 20));
			addDrawableChild(ButtonWidget.builder(getToggleText(), button -> {
				config.autoClickerEnabled = !config.autoClickerEnabled;
				config.save();
				button.setMessage(getToggleText());
			}).dimensions(buttonX, panelY + 70, buttonWidth, 20).build());

			addDrawableChild(ButtonWidget.builder(getBotText(), button -> {
				if (bot.isRunning()) {
					bot.stop();
				} else {
					bot.start();
				}
				button.setMessage(getBotText());
			}).dimensions(buttonX, panelY + 98, buttonWidth, 20).build());

			addDrawableChild(ButtonWidget.builder(Text.literal("Set A = my pos"), button -> {
				setPointToCurrent(config.pointA);
				button.setMessage(Text.literal("A: " + pointText(config.pointA)));
			}).dimensions(buttonX, panelY + 126, halfWidth, 20).build());
			addDrawableChild(ButtonWidget.builder(Text.literal("Set B = my pos"), button -> {
				setPointToCurrent(config.pointB);
				button.setMessage(Text.literal("B: " + pointText(config.pointB)));
			}).dimensions(buttonX + halfWidth + 6, panelY + 126, halfWidth, 20).build());

			addDrawableChild(ButtonWidget.builder(getLoopText(), button -> {
				config.loop = !config.loop;
				config.save();
				button.setMessage(getLoopText());
			}).dimensions(buttonX, panelY + 154, halfWidth, 20).build());
			addDrawableChild(ButtonWidget.builder(getAvoidText(), button -> {
				config.avoidPlayers = !config.avoidPlayers;
				config.save();
				button.setMessage(getAvoidText());
			}).dimensions(buttonX + halfWidth + 6, panelY + 154, halfWidth, 20).build());

			addDrawableChild(ButtonWidget.builder(Text.literal("Done"), button -> close())
					.dimensions(buttonX, panelY + 182, buttonWidth, 20)
					.build());
		}

		private void setPointToCurrent(int[] point) {
			if (client != null && client.player != null) {
				BlockPos pos = client.player.getBlockPos();
				point[0] = pos.getX();
				point[1] = pos.getY();
				point[2] = pos.getZ();
				config.save();
			}
		}

		@Override
		public void render(DrawContext context, int mouseX, int mouseY, float delta) {
			renderBackground(context, mouseX, mouseY, delta);

			int panelX = (width - PANEL_WIDTH) / 2;
			int panelY = (height - PANEL_HEIGHT) / 2;
			context.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, 0xCC101010);
			context.drawBorder(panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, 0xFF707070);
			context.drawCenteredTextWithShadow(textRenderer, title, width / 2, panelY + 12, 0xFFFFFF);
			context.drawCenteredTextWithShadow(textRenderer, Text.literal("A: " + pointText(config.pointA) + "   B: " + pointText(config.pointB)), width / 2, panelY + 27, 0xA0A0A0);

			super.render(context, mouseX, mouseY, delta);
		}

		@Override
		public void close() {
			config.save();
			client.setScreen(parent);
		}

		@Override
		public boolean shouldPause() {
			return false;
		}

		private Text getToggleText() {
			return Text.literal("Auto clicker: " + (config.autoClickerEnabled ? "ON" : "OFF"));
		}

		private Text getBotText() {
			return Text.literal("Walker bot: " + (bot.isRunning() ? "ON" : "OFF"));
		}

		private Text getLoopText() {
			return Text.literal("Loop: " + (config.loop ? "ON" : "OFF"));
		}

		private Text getAvoidText() {
			return Text.literal("Avoid: " + (config.avoidPlayers ? "ON" : "OFF"));
		}

		private String pointText(int[] p) {
			if (p == null || p.length != 3) {
				return "-";
			}
			return p[0] + " " + p[1] + " " + p[2];
		}
	}

	private class SpeedSlider extends SliderWidget {
		private SpeedSlider(int x, int y, int width, int height) {
			super(x, y, width, height, Text.empty(), (config.clicksPerSecond - MIN_CLICKS_PER_SECOND) / (double)(MAX_CLICKS_PER_SECOND - MIN_CLICKS_PER_SECOND));
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Text.literal("Speed: " + config.clicksPerSecond + " clicks/sec"));
		}

		@Override
		protected void applyValue() {
			int value = MIN_CLICKS_PER_SECOND + (int)Math.round(this.value * (MAX_CLICKS_PER_SECOND - MIN_CLICKS_PER_SECOND));
			config.clicksPerSecond = Math.clamp(value, MIN_CLICKS_PER_SECOND, MAX_CLICKS_PER_SECOND);
			config.save();
		}
	}
}
