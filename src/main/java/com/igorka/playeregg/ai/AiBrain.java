package com.igorka.playeregg.ai;

import com.google.gson.*;
import com.igorka.playeregg.PlayerEggMod;
import com.igorka.playeregg.entity.ClonePlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * СЛОЙ ПРИНЯТИЯ РЕШЕНИЙ (высокоуровневый).
 *
 * Единственная задача — раз в N тиков асинхронно спросить LLM «какое состояние выбрать»
 * и записать результат в NpcBlackboard. НИКОГДА не блокирует игровой поток и не управляет
 * телом напрямую: этим занимается NpcController каждый тик.
 */
public class AiBrain {
	private static final Gson GSON = new Gson();
	private static final int MAX_HISTORY = 6;

	private final ClonePlayerEntity npc;
	private final NpcBlackboard bb;
	private final Deque<GroqClient.Message> history = new ArrayDeque<>();
	private final AtomicBoolean requestInFlight = new AtomicBoolean(false);
	private final List<String> pendingChat = new ArrayList<>();

	private int tickCounter;
	private int failStreak;
	private long lastErrorReport;

	public AiBrain(ClonePlayerEntity npc, NpcBlackboard bb) {
		this.npc = npc;
		this.bb = bb;
	}

	/** Реплика игрока рядом — повод подумать немедленно. */
	public synchronized void hearChat(String playerName, String message) {
		pendingChat.add(playerName + ": " + message);
	}

	public void tick() {
		if (!ServerAiSettings.enabled() || !ServerAiSettings.hasKey()) return;
		// Запрос уже в полёте — просто выходим. Бот тем временем живёт по FSM.
		if (requestInFlight.get()) return;

		boolean urgent;
		synchronized (this) { urgent = !pendingChat.isEmpty(); }

		tickCounter++;
		if (!urgent && tickCounter < ServerAiSettings.thinkIntervalTicks()) return;
		tickCounter = 0;

		think();
	}

	private void think() {
		final String state = buildWorldState();

		List<GroqClient.Message> messages = new ArrayList<>();
		messages.add(new GroqClient.Message("system", systemPrompt()));
		synchronized (this) { messages.addAll(history); }
		messages.add(new GroqClient.Message("user", state));

		requestInFlight.set(true);

		// АСИНХРОННО: игровой поток продолжает тикать, пока ждём ответ.
		GroqClient.chat(ServerAiSettings.apiKey(), messages)
				.thenAccept(reply -> {
					try {
						if (reply == null) { failStreak++; reportError(); return; }
						failStreak = 0;

						AiAction action = parse(reply);
						if (action == null) {
							if (ServerAiSettings.debug())
								PlayerEggMod.LOGGER.warn("[PlayerEgg] Не JSON: {}", reply);
							return;
						}

						// Применяем ТОЛЬКО в главном потоке сервера — потокобезопасность.
						var server = npc.getServer();
						if (server != null) server.execute(() -> {
							if (npc.isAlive()) npc.applyDecision(action);
						});

						synchronized (this) {
							history.addLast(new GroqClient.Message("user", state));
							history.addLast(new GroqClient.Message("assistant", reply));
							while (history.size() > MAX_HISTORY) history.removeFirst();
						}
					} finally {
						requestInFlight.set(false);
					}
				})
				.exceptionally(t -> { requestInFlight.set(false); failStreak++; return null; });
	}

	private void reportError() {
		String err = GroqClient.lastError;
		if (err == null) return;
		long now = System.currentTimeMillis();
		if (failStreak >= 2 && now - lastErrorReport > 20000) {
			lastErrorReport = now;
			var server = npc.getServer();
			if (server != null) server.execute(() -> npc.say("[ошибка ИИ] " + err));
		}
	}

	/** Промпт содержит слово "json" — обязательное условие json_object режима. */
	private String systemPrompt() {
		return """
				Ты — мозг NPC-игрока в Minecraft по имени %s. Характер: %s

				Ты работаешь как ПЛАНИРОВЩИК высокого уровня. Ты НЕ управляешь каждым шагом:
				низкоуровневые навигация, бой и добыча выполняются движком автономно.
				Ты лишь выбираешь СОСТОЯНИЕ и цель. Отвечай одним json-объектом:

				{
				  "action": "<состояние>",
				  "x": 0, "y": 0, "z": 0,
				  "target": "ник игрока или тип моба",
				  "block": "minecraft:stone",
				  "resource": "diamond_ore",
				  "item": "minecraft:diamond_pickaxe",
				  "jump": false, "sneak": false, "sprint": false,
				  "say": ""
				}

				Состояния "action":
				- "stop"    — стоять неподвижно
				- "move_to" — идти к x,y,z (движок сам построит путь и обойдёт препятствия)
				- "follow"  — следовать за игроком target (движок сам догоняет)
				- "patrol"  — патрулировать местность вокруг
				- "attack"  — атаковать target (движок сам преследует и бьёт)
				- "mine"    — сломать блок в x,y,z
				- "build"   — поставить block в x,y,z
				- "seek"    — найти и добыть ресурс resource поблизости
				- "equip"   — взять item в руку
				- "drop", "swing", "look_at"

				Правила:
				1. Всегда выполняй прямую просьбу игрока из chat_messages.
				2. Если тебе написали — обязательно заполни "say". Не молчи.
				3. Задача продолжается сама, пока ты её не сменишь. Видишь current_state — не сбрасывай его без причины.
				4. Координаты бери только из присланных данных.
				5. Отвечай коротко, до 15 слов, на языке собеседника.
				""".formatted(safe(npc.getNpcName()), safe(ServerAiSettings.personality()));
	}

	private static String safe(String s) {
		return s == null ? "" : s.replace("\"", "'");
	}

	/** Компактный снимок мира. Собирается в главном потоке — быстро, без сканов. */
	private String buildWorldState() {
		World world = npc.getWorld();
		BlockPos pos = npc.getBlockPos();

		JsonObject root = new JsonObject();
		root.addProperty("my_name", npc.getNpcName());
		root.addProperty("current_state", bb.describe());

		JsonObject self = new JsonObject();
		self.addProperty("x", round(npc.getX()));
		self.addProperty("y", round(npc.getY()));
		self.addProperty("z", round(npc.getZ()));
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
		env.addProperty("light", world.getLightLevel(pos));
		env.addProperty("biome", world.getBiome(pos).getKey()
				.map(k -> k.getValue().getPath()).orElse("unknown"));
		root.add("environment", env);

		Direction dir = npc.getHorizontalFacing();
		BlockPos front = pos.offset(dir);
		JsonObject blocks = new JsonObject();
		blocks.addProperty("below", blockName(world, pos.down()));
		blocks.addProperty("front", blockName(world, front));
		blocks.addProperty("front_up", blockName(world, front.up()));
		blocks.addProperty("obstacle_ahead",
				!world.getBlockState(front).getCollisionShape(world, front).isEmpty());
		root.add("blocks", blocks);

		JsonObject coords = new JsonObject();
		coords.add("block_in_front", posJson(front));
		coords.add("block_below_me", posJson(pos.down()));
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
			players.add(jp);
		}
		root.add("players_nearby", players);

		JsonArray mobs = new JsonArray();
		int count = 0;
		for (Entity e : world.getOtherEntities(npc, new Box(pos).expand(16))) {
			if (!(e instanceof LivingEntity) || e instanceof PlayerEntity) continue;
			if (++count > 6) break;
			JsonObject jm = new JsonObject();
			jm.addProperty("type", e.getType().getUntranslatedName().replace("entity.minecraft.", ""));
			jm.addProperty("distance", round(e.distanceTo(npc)));
			mobs.add(jm);
		}
		root.add("mobs_nearby", mobs);

		JsonArray chat = new JsonArray();
		synchronized (this) { pendingChat.forEach(chat::add); pendingChat.clear(); }
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
		return Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).getPath();
	}

	private static double round(double v) {
		return Math.round(v * 10.0) / 10.0;
	}

	private static AiAction parse(String reply) {
		try {
			String s = reply.trim();
			int a = s.indexOf('{'), b = s.lastIndexOf('}');
			if (a < 0 || b <= a) return null;
			return GSON.fromJson(JsonParser.parseString(s.substring(a, b + 1)).getAsJsonObject(), AiAction.class);
		} catch (Exception e) {
			return null;
		}
	}
}
