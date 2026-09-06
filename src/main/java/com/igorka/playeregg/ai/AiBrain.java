package com.igorka.playeregg.ai;

import com.google.gson.*;
import com.igorka.playeregg.PlayerEggMod;
import com.igorka.playeregg.entity.ClonePlayerEntity;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Мозг NPC: собирает состояние мира, спрашивает Groq, применяет решение. */
public class AiBrain {
	private static final Gson GSON = new Gson();
	private static final int MAX_HISTORY = 8;   // 4 пары «запрос-ответ»

	private final ClonePlayerEntity npc;
	private final Deque<GroqClient.Message> history = new ArrayDeque<>();
	private final AtomicBoolean busy = new AtomicBoolean(false);
	private final List<String> pendingChat = new ArrayList<>();

	private int tickCounter;
	private int failStreak;
	private long lastErrorReport;

	public AiBrain(ClonePlayerEntity npc) {
		this.npc = npc;
	}

	/** Реплика игрока рядом — обрабатываем немедленно. */
	public synchronized void hearChat(String playerName, String message) {
		pendingChat.add(playerName + ": " + message);
		tickCounter = Integer.MAX_VALUE - 1;   // сработает на ближайшем тике
	}

	public void tick() {
		if (!ServerAiSettings.enabled() || !ServerAiSettings.hasKey()) return;

		boolean urgent;
		synchronized (this) { urgent = !pendingChat.isEmpty(); }

		if (busy.get()) return;
		tickCounter++;
		if (!urgent && tickCounter < ServerAiSettings.thinkIntervalTicks()) return;
		tickCounter = 0;

		think(urgent);
	}

	private void think(boolean urgent) {
		final String state = buildWorldState();

		List<GroqClient.Message> messages = new ArrayList<>();
		messages.add(new GroqClient.Message("system", systemPrompt()));
		synchronized (this) { messages.addAll(history); }
		messages.add(new GroqClient.Message("user", state));

		busy.set(true);
		GroqClient.chat(ServerAiSettings.apiKey(), messages)
				.thenAccept(reply -> {
					try {
						if (reply == null) {
							failStreak++;
							reportError();
							return;
						}
						failStreak = 0;

						AiAction action = parse(reply);
						if (action == null) {
							if (ServerAiSettings.debug())
								PlayerEggMod.LOGGER.warn("[PlayerEgg] Не JSON: {}", reply);
							return;
						}

						var server = npc.getServer();
						if (server != null) server.execute(() -> {
							if (npc.isAlive()) npc.applyAction(action);
						});

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
					failStreak++;
					return null;
				});
	}

	/** Чтобы NPC не «молчал непонятно почему» — раз в 15 сек сообщаем о проблеме. */
	private void reportError() {
		String err = GroqClient.lastError;
		if (err == null) return;
		long now = System.currentTimeMillis();
		if (failStreak >= 2 && now - lastErrorReport > 15000) {
			lastErrorReport = now;
			var server = npc.getServer();
			if (server != null) server.execute(() ->
					npc.say("[ошибка ИИ] " + err));
		}
	}

	private String systemPrompt() {
		return """
				Ты управляешь NPC-игроком в Minecraft. Твоё имя: %s. Характер: %s

				Тебе приходит JSON с состоянием мира. Ты ОБЯЗАН ответить ровно одним JSON-объектом, без markdown и пояснений:
				{
				  "action": "<действие>",
				  "x": 0, "y": 0, "z": 0,
				  "target": "ник или тип моба",
				  "block": "minecraft:stone",
				  "item": "minecraft:diamond_pickaxe",
				  "jump": false, "sneak": false, "sprint": false,
				  "say": "",
				  "then": []
				}

				Доступные "action":
				- "stop"        — стоять неподвижно (по умолчанию)
				- "move_to"     — идти к координатам x,y,z
				- "follow"      — идти за игроком target
				- "wander"      — бродить рядом
				- "look_at"     — повернуться к target
				- "break_block" — сломать блок в x,y,z (не дальше 6 блоков)
				- "place_block" — поставить block в x,y,z (не дальше 6 блоков)
				- "attack"      — ударить target (ник игрока или тип моба, например "zombie")
				- "equip"       — взять item в руку
				- "use_item"    — использовать предмет в руке
				- "drop"        — выбросить предмет
				- "swing"       — махнуть рукой
				Поле "then" — массив таких же объектов, если нужно несколько действий подряд (например поставить 3 блока).

				КРИТИЧЕСКИ ВАЖНЫЕ ПРАВИЛА:
				1. ВСЕГДА выполняй прямую просьбу игрока из chat_messages. Если просят идти за ним — "follow" с его ником.
				   Если просят остановиться — "stop". Если просят прыгнуть — "jump": true. Если просят присесть — "sneak": true.
				   Если просят сломать/поставить блок — используй координаты из блоков рядом.
				2. Если тебе написали в чат — ОБЯЗАТЕЛЬНО заполни "say" ответом. Никогда не молчи в ответ на вопрос.
				3. Если сообщений нет и делать нечего — верни {"action":"stop","say":""}. Не выдумывай лишних действий.
				4. Продолжай текущую задачу (current_task), пока её не отменили: не сбрасывай "follow" на "stop" без причины.
				5. Координаты бери ТОЛЬКО из присланных данных (блоки рядом, позиции игроков). Не выдумывай.
				6. Реплики короткие, до 15 слов, на языке собеседника.
				7. "sneak" остаётся включённым, пока ты не выключишь его явно ("sneak": false).
				""".formatted(safe(npc.getNpcName()), safe(ServerAiSettings.personality()));
	}

	private static String safe(String s) {
		return s == null ? "" : s.replace("\"", "'");
	}

	private String buildWorldState() {
		World world = npc.getWorld();
		BlockPos pos = npc.getBlockPos();

		JsonObject root = new JsonObject();
		root.addProperty("my_name", npc.getNpcName());
		root.addProperty("current_task", npc.currentTask());

		JsonObject self = new JsonObject();
		self.addProperty("x", round(npc.getX()));
		self.addProperty("y", round(npc.getY()));
		self.addProperty("z", round(npc.getZ()));
		self.addProperty("yaw", Math.round(npc.getYaw()));
		self.addProperty("facing", npc.getHorizontalFacing().asString());
		self.addProperty("health", Math.round(npc.getHealth()));
		self.addProperty("on_ground", npc.isOnGround());
		self.addProperty("in_water", npc.isTouchingWater());
		self.addProperty("sneaking", npc.isSneakingNpc());
		self.addProperty("holding", itemName(npc.getMainHandStack()));
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

		JsonObject blocks = new JsonObject();
		blocks.addProperty("below", blockName(world, pos.down()));
		blocks.addProperty("at_feet", blockName(world, pos));
		blocks.addProperty("at_head", blockName(world, pos.up()));

		Direction dir = npc.getHorizontalFacing();
		BlockPos front = pos.offset(dir);
		blocks.addProperty("front", blockName(world, front));
		blocks.addProperty("front_up", blockName(world, front.up()));
		blocks.addProperty("front_below", blockName(world, front.down()));
		blocks.addProperty("obstacle_ahead",
				!world.getBlockState(front).getCollisionShape(world, front).isEmpty());
		blocks.addProperty("drop_ahead",
				world.getBlockState(front.down()).isAir() && world.getBlockState(front.down().down()).isAir());
		root.add("blocks", blocks);

		// координаты ближайших целей для break/place — чтобы модель не выдумывала
		JsonObject coords = new JsonObject();
		coords.add("block_in_front", posJson(front));
		coords.add("block_below_me", posJson(pos.down()));
		coords.add("space_in_front", posJson(front));
		coords.add("space_above_front", posJson(front.up()));
		root.add("useful_coords", coords);

		JsonArray players = new JsonArray();
		for (PlayerEntity p : world.getPlayers()) {
			double d = p.distanceTo(npc);
			if (d > 48) continue;
			JsonObject jp = new JsonObject();
			jp.addProperty("name", p.getGameProfile().getName());
			jp.addProperty("distance", round(d));
			jp.addProperty("x", round(p.getX()));
			jp.addProperty("y", round(p.getY()));
			jp.addProperty("z", round(p.getZ()));
			jp.addProperty("holding", itemName(p.getMainHandStack()));
			players.add(jp);
		}
		root.add("players_nearby", players);

		JsonArray mobs = new JsonArray();
		int count = 0;
		for (Entity e : world.getOtherEntities(npc, new Box(npc.getBlockPos()).expand(16))) {
			if (!(e instanceof LivingEntity) || e instanceof PlayerEntity) continue;
			if (++count > 8) break;
			JsonObject jm = new JsonObject();
			jm.addProperty("type", e.getType().getUntranslatedName()
					.replace("entity.minecraft.", ""));
			jm.addProperty("distance", round(e.distanceTo(npc)));
			jm.addProperty("x", round(e.getX()));
			jm.addProperty("y", round(e.getY()));
			jm.addProperty("z", round(e.getZ()));
			mobs.add(jm);
		}
		root.add("mobs_nearby", mobs);

		JsonArray chat = new JsonArray();
		synchronized (this) {
			pendingChat.forEach(chat::add);
			pendingChat.clear();
		}
		root.add("chat_messages", chat);

		return GSON.toJson(root);
	}

	private static JsonObject posJson(BlockPos p) {
		JsonObject o = new JsonObject();
		o.addProperty("x", p.getX());
		o.addProperty("y", p.getY());
		o.addProperty("z", p.getZ());
		return o;
	}

	private static String itemName(net.minecraft.item.ItemStack st) {
		return st.isEmpty() ? "nothing" : Registries.ITEM.getId(st.getItem()).toString();
	}

	private static String blockName(World world, BlockPos pos) {
		BlockState st = world.getBlockState(pos);
		return Registries.BLOCK.getId(st.getBlock()).getPath();
	}

	private static double round(double v) {
		return Math.round(v * 10.0) / 10.0;
	}

	private static AiAction parse(String reply) {
		try {
			String s = reply.trim();
			int start = s.indexOf('{');
			int end = s.lastIndexOf('}');
			if (start < 0 || end <= start) return null;
			JsonObject o = JsonParser.parseString(s.substring(start, end + 1)).getAsJsonObject();
			return GSON.fromJson(o, AiAction.class);
		} catch (Exception e) {
			return null;
		}
	}
}
