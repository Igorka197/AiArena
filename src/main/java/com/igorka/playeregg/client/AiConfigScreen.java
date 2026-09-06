package com.igorka.playeregg.client;

import com.igorka.playeregg.ai.AiConfig;
import com.igorka.playeregg.net.AiNetworking;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/** Меню ввода Groq API-ключа и настроек ИИ. */
@Environment(EnvType.CLIENT)
public class AiConfigScreen extends Screen {

	private static final List<String> MODELS = List.of(
			"llama-3.3-70b-versatile",
			"llama-3.1-8b-instant",
			"openai/gpt-oss-20b",
			"openai/gpt-oss-120b",
			"qwen/qwen3-32b"
	);

	private final Screen parent;
	private TextFieldWidget keyField;
	private TextFieldWidget personalityField;
	private TextFieldWidget intervalField;
	private String model;
	private boolean enabled;
	private boolean showKey = false;

	public AiConfigScreen(Screen parent) {
		super(Text.translatable("screen.playeregg.ai_config"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		AiConfig cfg = AiConfig.get();
		this.model = cfg.model;
		this.enabled = cfg.enabled;

		int cx = this.width / 2;
		int y = 50;

		keyField = new TextFieldWidget(this.textRenderer, cx - 150, y, 300, 20,
				Text.translatable("screen.playeregg.api_key"));
		keyField.setMaxLength(256);
		keyField.setText(cfg.apiKey);
		keyField.setRenderTextProvider((text, idx) ->
				showKey ? Text.literal(text).asOrderedText()
						: Text.literal("*".repeat(text.length())).asOrderedText());
		addSelectableChild(keyField);
		setInitialFocus(keyField);

		addDrawableChild(ButtonWidget.builder(Text.literal(showKey ? "🙈" : "👁"),
						b -> { showKey = !showKey; this.clearAndInit(); })
				.dimensions(cx + 152, y, 20, 20).build());

		y += 46;
		addDrawableChild(CyclingButtonWidget.<String>builder(Text::literal)
				.values(MODELS)
				.initially(MODELS.contains(model) ? model : MODELS.get(0))
				.build(cx - 150, y, 300, 20, Text.translatable("screen.playeregg.model"),
						(btn, val) -> this.model = val));

		y += 46;
		personalityField = new TextFieldWidget(this.textRenderer, cx - 150, y, 300, 20,
				Text.translatable("screen.playeregg.personality"));
		personalityField.setMaxLength(512);
		personalityField.setText(cfg.personality);
		addSelectableChild(personalityField);

		y += 46;
		intervalField = new TextFieldWidget(this.textRenderer, cx - 150, y, 140, 20,
				Text.translatable("screen.playeregg.interval"));
		intervalField.setMaxLength(5);
		intervalField.setText(String.valueOf(cfg.thinkIntervalTicks));
		addSelectableChild(intervalField);

		addDrawableChild(CyclingButtonWidget.onOffBuilder(this.enabled)
				.build(cx + 10, y, 140, 20, Text.translatable("screen.playeregg.enabled"),
						(btn, val) -> this.enabled = val));

		y += 40;
		addDrawableChild(ButtonWidget.builder(Text.translatable("screen.playeregg.save"), b -> save())
				.dimensions(cx - 150, y, 145, 20).build());
		addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), b -> close())
				.dimensions(cx + 5, y, 145, 20).build());

		y += 24;
		addDrawableChild(ButtonWidget.builder(Text.literal("Получить бесплатный ключ: console.groq.com/keys"),
						b -> this.client.setScreen(new net.minecraft.client.gui.screen.ConfirmLinkScreen(
								ok -> {
									if (ok) net.minecraft.util.Util.getOperatingSystem()
											.open("https://console.groq.com/keys");
									this.client.setScreen(this);
								}, "https://console.groq.com/keys", true)))
				.dimensions(cx - 150, y, 300, 20).build());
	}

	private void save() {
		AiConfig cfg = AiConfig.get();
		cfg.apiKey = keyField.getText().trim();
		cfg.model = model;
		cfg.personality = personalityField.getText().trim();
		cfg.enabled = enabled;
		try {
			cfg.thinkIntervalTicks = Math.max(20, Integer.parseInt(intervalField.getText().trim()));
		} catch (NumberFormatException ignored) {
			cfg.thinkIntervalTicks = 60;
		}
		cfg.save();

		// отправляем на сервер
		if (this.client != null && this.client.getNetworkHandler() != null) {
			PacketByteBuf buf = PacketByteBufs.create();
			buf.writeString(cfg.apiKey, 256);
			buf.writeString(cfg.model, 128);
			buf.writeString(cfg.personality, 512);
			buf.writeVarInt(cfg.thinkIntervalTicks);
			buf.writeBoolean(cfg.enabled);
			ClientPlayNetworking.send(AiNetworking.SETTINGS, buf);
		}
		close();
	}

	@Override
	public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
		this.renderBackground(ctx);
		ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 20, 0xFFFFFF);

		int cx = this.width / 2;
		ctx.drawTextWithShadow(this.textRenderer,
				Text.translatable("screen.playeregg.api_key").formatted(Formatting.GRAY), cx - 150, 38, 0xA0A0A0);
		ctx.drawTextWithShadow(this.textRenderer,
				Text.translatable("screen.playeregg.model").formatted(Formatting.GRAY), cx - 150, 84, 0xA0A0A0);
		ctx.drawTextWithShadow(this.textRenderer,
				Text.translatable("screen.playeregg.personality").formatted(Formatting.GRAY), cx - 150, 130, 0xA0A0A0);
		ctx.drawTextWithShadow(this.textRenderer,
				Text.translatable("screen.playeregg.interval").formatted(Formatting.GRAY), cx - 150, 176, 0xA0A0A0);

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
	public boolean shouldPause() {
		return false;
	}
}
