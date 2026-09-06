package com.igorka.playeregg.ai;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.igorka.playeregg.PlayerEggMod;
import com.igorka.playeregg.entity.ClonePlayerEntity;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Мозг NPC: собирает состояние мира, спрашивает Groq, применяет решение. */
public class AiBrain {
	private static final Gson GSON = new Gson();
	private static final int MAX_HISTORY = 12;

	private final ClonePlayerEntity npc;
	private final Deque<GroqClient.Message> history = new ArrayDeque<>();
	private final AtomicBoolean busy = new AtomicBoolean(false);
	private final List<String> pendingChat = new ArrayList<>();

	private int tickCounter;

	public AiBrain(ClonePlayerEntity npc) {
		this.npc = npc;
	}

	/** Сообщение игрока в чате рядом с NPC — попадёт в следующий запрос. */
	public synchronized void hearChat(String playerName, String message) {
		pendingChat.add(playerName + ": " + message);
		// на реплику реагируем быстро
		tickCounter = ServerAiSettings.thinkIntervalTicks();
	}

	public void tick() {
		if (!ServerAiSettings.enabled() || !ServerAiSettings.hasKey()) return;
		if (busy.get()) return;

		if (++tickCounter < ServerAiSettings.thinkIntervalTicks()) return;
		tickCounter = 0;

		think();
	}

	private void think() {
		String state = buildWorldState();

		List<GroqClient.Message> messages = new ArrayList<>();
		messages.add(new GroqClient.Message("system", systemPrompt()));
		messages.addAll(history);
		messages.add(new GroqClient.Message("user", state));

		busy.set(true);
		GroqClient.chat(ServerAiSettings.apiKey(), ServerAiSettings.model(), messages)
				.thenAccept(reply -> {
					try {
						if (reply == null) return;
						AiAction action = parse(reply);
						if (action == null) return;

						// применяем в главном потоке сервера
						npc.getServer().execute(() -> npc.applyAction(action));

						synchronized (this) {
							history.addLast(new GroqClient.Message("user", state));
							history.addLast(new GroqClient.Message("assistant", reply));
							while (history.size() > MAX_HISTORY) history.removeFirst();
						}
					} finally {
						busy.set(false);
					}
				})
				.exceptionally(t -> {
					busy.set(false);
					return null;
				});
	}

	private String systemPrompt() {
		return """
				Ты — NPC-игрок в Minecraft по имени %s. Характер: %s
				Тебе присылают JSON с состоянием мира вокруг тебя. Ты отвечаешь ТОЛЬКО валидным JSON-объектом:
				{
				  "action": "move_to" | "follow" | "wander" | "stop" | "look_at",
				  "x": число, "y": число, "z": число,   // только для move_to
				  "target": "ник",                       // только для follow / look_at
				  "jump": true|false,
				  "sneak": true|false,
				  "sprint": true|false,
				  "say": "короткая реплика в чат или пустая строка"
				}
				Правила:
				- Никакого текста вне JSON.
				- Координаты бери из присланного состояния (блоки рядом, позиции игроков). Не выдумывай далёкие точки.
				- Если рядом игрок написал тебе в чат — обязательно ответь через поле "say".
				- Говори коротко (до 15 слов), на языке собеседника.
				- Прыгай (jump), если перед тобой препятствие высотой в 1 блок или ты в воде.
				- Приседай (sneak), если стоишь у края обрыва или прячешься.
				- Не спамь: если сказать нечего, "say": "".
				""".formatted(safe(npcName()), safe(ServerAiSettings.personality()));
	}

	private String npcName() {
		return npc.getNpcName();
	}

	private static String safe(String s) {
		return s == null ? "" : s.replace("\"", "'");
	}

	/** Собираем инфу об окружении для модели. */
	private String buildWorldState() {
		World world = npc.getWorld();
		BlockPos pos = npc.getBlockPos();

		JsonObject root = new JsonObject();
		root.addProperty("my_name", npcName());

		JsonObject self = new JsonObject();
		self.addProperty("x", round(npc.getX()));
		self.addProperty("y", round(npc.getY()));
		self.addProperty("z", round(npc.getZ()));
		self.addProperty("yaw", Math.round(npc.getYaw()));
		self.addProperty("health", Math.round(npc.getHealth()));
		self.addProperty("on_ground", npc.isOnGround());
		self.addProperty("in_water", npc.isTouchingWater());
		self.addProperty("sneaking", npc.isSneakingNpc());
		root.add("self", self);

		JsonObject env = new JsonObject();
		env.addProperty("time", world.getTimeOfDay() % 24000);
		env.addProperty("is_day", world.isDay());
		env.addProperty("raining", world.isRaining());
		env.addProperty("dimension", world.getRegistryKey().getValue().toString());
		env.addProperty("light", world.getLightLevel(pos));
		env.addProperty("biome", world.getBiome(pos).getKey()
				.map(k -> k.getValue().getPath()).orElse("unknown"));
		root.add("environment", env);

		// блоки вокруг: что под ногами, спереди, есть ли препятствие
		JsonObject blocks = new JsonObject();
		blocks.addProperty("below", blockName(world, pos.down()));
		blocks.addProperty("at_feet", blockName(world, pos));
		blocks.addProperty("at_head", blockName(world, pos.up()));

		BlockPos front = pos.offset(npc.getHorizontalFacing());
		blocks.addProperty("front", blockName(world, front));
		blocks.addProperty("front_up", blockName(world, front.up()));
		blocks.addProperty("front_below", blockName(world, front.down()));
		blocks.addProperty("obstacle_ahead",
				!world.getBlockState(front).getCollisionShape(world, front).isEmpty()
						&& world.getBlockState(front.up()).getCollisionShape(world, front.up()).isEmpty());
		blocks.addProperty("drop_ahead",
				world.getBlockState(front.down()).isAir()
						&& world.getBlockState(front.down().down()).isAir());
		root.add("blocks", blocks);

		// игроки поблизости
		var players = new com.google.gson.JsonArray();
		for (PlayerEntity p : world.getPlayers()) {
			double d = p.distanceTo(npc);
			if (d > 40) continue;
			JsonObject jp = new JsonObject();
			jp.addProperty("name", p.getGameProfile().getName());
			jp.addProperty("distance", round(d));
			jp.addProperty("x", round(p.getX()));
			jp.addProperty("y", round(p.getY()));
			jp.addProperty("z", round(p.getZ()));
			jp.addProperty("holding", p.getMainHandStack().isEmpty()
					? "nothing" : p.getMainHandStack().getItem().toString());
			players.add(jp);
		}
		root.add("players_nearby", players);

		// мобы поблизости
		var mobs = new com.google.gson.JsonArray();
		List<Entity> nearby = world.getOtherEntities(npc, new Box(npc.getBlockPos()).expand(16));
		int count = 0;
		for (Entity e : nearby) {
			if (!(e instanceof LivingEntity) || e instanceof PlayerEntity) continue;
			if (++count > 8) break;
			JsonObject jm = new JsonObject();
			jm.addProperty("type", e.getType().getUntranslatedName());
			jm.addProperty("distance", round(e.distanceTo(npc)));
			mobs.add(jm);
		}
		root.add("mobs_nearby", mobs);

		// чат
		var chat = new com.google.gson.JsonArray();
		synchronized (this) {
			pendingChat.forEach(chat::add);
			pendingChat.clear();
		}
		root.add("chat_messages", chat);

		return GSON.toJson(root);
	}

	private static String blockName(World world, BlockPos pos) {
		BlockState st = world.getBlockState(pos);
		return Registries.BLOCK.getId(st.getBlock()).getPath();
	}

	private static double round(double v) {
		return Math.round(v * 10.0) / 10.0;
	}

	/** Достаём JSON даже если модель обернула его в текст/```json. */
	private static AiAction parse(String reply) {
		try {
			String s = reply.trim();
			int start = s.indexOf('{');
			int end = s.lastIndexOf('}');
			if (start < 0 || end <= start) return null;
			JsonObject o = JsonParser.parseString(s.substring(start, end + 1)).getAsJsonObject();
			return GSON.fromJson(o, AiAction.class);
		} catch (Exception e) {
			PlayerEggMod.LOGGER.debug("[PlayerEgg] Плохой JSON от модели: {}", reply);
			return null;
		}
	}
}
