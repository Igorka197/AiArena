package com.igorka.playeregg.client;

import com.igorka.playeregg.ai.AiConfig;
import com.igorka.playeregg.ai.ServerAiSettings;
import com.igorka.playeregg.net.AiNetworking;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;

/** Меню ввода Groq API-ключа и настроек ИИ. */
@Environment(EnvType.CLIENT)
public class AiConfigScreen extends Screen {

	private final Screen parent;
	private TextFieldWidget keyField;
	private TextFieldWidget personalityField;
	private TextFieldWidget intervalField;
	private boolean enabled;
	private boolean debug;
	private boolean showKey = false;

	public AiConfigScreen(Screen parent) {
		super(Text.translatable("screen.playeregg.ai_config"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		AiConfig cfg = AiConfig.get();
		this.enabled = cfg.enabled;
		this.debug = cfg.debug;

		int cx = this.width / 2;
		int y = 46;

		keyField = new TextFieldWidget(this.textRenderer, cx - 150, y, 278, 20,
				Text.translatable("screen.playeregg.api_key"));
		keyField.setMaxLength(256);
		keyField.setText(cfg.apiKey);
		keyField.setRenderTextProvider((text, idx) ->
				showKey ? Text.literal(text).asOrderedText()
						: Text.literal("*".repeat(text.length())).asOrderedText());
		addSelectableChild(keyField);
		setInitialFocus(keyField);

		addDrawableChild(ButtonWidget.builder(Text.literal(showKey ? "*" : "O"),
						b -> { showKey = !showKey; this.clearAndInit(); })
				.dimensions(cx + 130, y, 20, 20).build());

		y += 42;
		personalityField = new TextFieldWidget(this.textRenderer, cx - 150, y, 300, 20,
				Text.translatable("screen.playeregg.personality"));
		personalityField.setMaxLength(512);
		personalityField.setText(cfg.personality);
		addSelectableChild(personalityField);

		y += 42;
		intervalField = new TextFieldWidget(this.textRenderer, cx - 150, y, 140, 20,
				Text.translatable("screen.playeregg.interval"));
		intervalField.setMaxLength(4);
		intervalField.setText(String.valueOf(cfg.thinkIntervalTicks));
		addSelectableChild(intervalField);

		addDrawableChild(CyclingButtonWidget.onOffBuilder(this.enabled)
				.build(cx + 10, y, 140, 20, Text.translatable("screen.playeregg.enabled"),
						(btn, val) -> this.enabled = val));

		y += 26;
		addDrawableChild(CyclingButtonWidget.onOffBuilder(this.debug)
				.build(cx + 10, y, 140, 20, Text.translatable("screen.playeregg.debug"),
						(btn, val) -> this.debug = val));

		y += 34;
		addDrawableChild(ButtonWidget.builder(Text.translatable("screen.playeregg.save"), b -> save())
				.dimensions(cx - 150, y, 145, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), b -> close())
				.dimensions(cx + 5, y, 145, 20).build());

		y += 24;
		addDrawableChild(ButtonWidget.builder(
						Text.translatable("screen.playeregg.get_key"),
						b -> this.client.setScreen(new ConfirmLinkScreen(ok -> {
							if (ok) Util.getOperatingSystem().open("https://console.groq.com/keys");
							this.client.setScreen(this);
						}, "https://console.groq.com/keys", true)))
				.dimensions(cx - 150, y, 300, 20).build());
	}

	private void save() {
		AiConfig cfg = AiConfig.get();
		cfg.apiKey = keyField.getText().trim();
		cfg.personality = personalityField.getText().trim();
		cfg.enabled = enabled;
		cfg.debug = debug;
		try {
			cfg.thinkIntervalTicks = Math.max(20, Math.min(400, Integer.parseInt(intervalField.getText().trim())));
		} catch (NumberFormatException ignored) {
			cfg.thinkIntervalTicks = 40;
		}
		cfg.save();
		sendToServer(cfg);
		close();
	}

	public static void sendToServer(AiConfig cfg) {
		if (net.minecraft.client.MinecraftClient.getInstance().getNetworkHandler() == null) return;
		PacketByteBuf buf = PacketByteBufs.create();
		buf.writeString(cfg.apiKey, 256);
		buf.writeString(cfg.personality, 512);
		buf.writeVarInt(cfg.thinkIntervalTicks);
		buf.writeBoolean(cfg.enabled);
		buf.writeBoolean(cfg.debug);
		ClientPlayNetworking.send(AiNetworking.SETTINGS, buf);
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		this.renderBackground(ctx);
		ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 16, 0xFFFFFF);
		ctx.drawCenteredTextWithShadow(this.textRenderer,
				Text.literal("Модель: " + ServerAiSettings.MODEL).formatted(Formatting.DARK_GRAY),
				this.width / 2, 28, 0x808080);

		int cx = this.width / 2;
		ctx.drawTextWithShadow(this.textRenderer,
				Text.translatable("screen.playeregg.api_key").formatted(Formatting.GRAY), cx - 150, 34, 0xA0A0A0);
		ctx.drawTextWithShadow(this.textRenderer,
				Text.translatable("screen.playeregg.personality").formatted(Formatting.GRAY), cx - 150, 76, 0xA0A0A0);
		ctx.drawTextWithShadow(this.textRenderer,
				Text.translatable("screen.playeregg.interval").formatted(Formatting.GRAY), cx - 150, 118, 0xA0A0A0);

		keyField.render(ctx, mouseX, mouseY, delta);
		personalityField.render(ctx, mouseX, mouseY, delta);
		intervalField.render(ctx, mouseX, mouseY, delta);
		super.render(ctx, mouseX, mouseY, delta);
	}

	@Override
	public void close() {
		if (this.client != null) this.client.setScreen(parent);
	}

	@Override
	public boolean shouldPause() { return false; }
}
