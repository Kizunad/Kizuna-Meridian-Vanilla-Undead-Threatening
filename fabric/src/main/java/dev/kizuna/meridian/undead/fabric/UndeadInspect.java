package dev.kizuna.meridian.undead.fabric;

import dev.kizuna.meridian.fabric.MeridianCommands;
import dev.kizuna.meridian.protocol.BodyProtobuf;
import dev.kizuna.meridian.protocol.pb.BodyMessages.BodySnapshot;
import dev.kizuna.meridian.protocol.pb.BodyMessages.PartState;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import java.util.Locale;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;

final class UndeadInspect {
    private static final double RANGE = 32;
    private UndeadInspect() { }

    static void register() {
        // 与查看他人身体的命令共用管理员权限，选择器由原版补全。
        MeridianCommands.register(MeridianCommands.Branch.BODY, "undead", MeridianCommands.GAMEMASTER,
                (node, context) -> node.then(Commands.literal("inspect")
                        .executes(ctx -> inspect(ctx.getSource(), aimed(ctx.getSource())))
                        .then(Commands.argument("target", EntityArgument.entity())
                                .executes(ctx -> inspect(ctx.getSource(), EntityArgument.getEntity(ctx, "target"))))));
    }

    private static Entity aimed(CommandSourceStack source) {
        // 以视线遇到的第一个方块截断拾取射线。
        Entity actor = source.getEntity();
        if (actor == null) {
            return null;
        }
        var eye = actor.getEyePosition();
        var end = actor.pick(RANGE, 1, false /* includeFluids */).getLocation();
        var bounds = actor.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1);
        var hit = ProjectileUtil.getEntityHitResult(actor, eye, end, bounds,
                target -> target instanceof LivingEntity && target.isPickable(), eye.distanceToSqr(end));
        if (hit == null) {
            return null;
        }
        return hit.getEntity();
    }

    private static int inspect(CommandSourceStack source, Entity entity) {
        if (entity == null) {
            source.sendFailure(Component.literal("请对准生物或指定一个目标"));
            return 0;
        }
        if (!(entity instanceof LivingEntity living) || !UndeadFabric.SPECIES.containsKey(entity.getType())) {
            source.sendFailure(Component.literal("目标不属于尸身物种"));
            return 0;
        }
        if (living.isBaby()) {
            source.sendFailure(Component.literal("幼体没有尸身"));
            return 0;
        }
        if (!UndeadFabric.accepts(living)) {
            source.sendFailure(Component.literal("此物种的尸身开关已关闭"));
            return 0;
        }
        var snapshot = VanillaDamageFabric.inspectBody(living);
        if (snapshot.isEmpty()) {
            source.sendFailure(Component.literal("身体宿主未返回尸身"));
            return 0;
        }
        String report = describe(snapshot.get());
        source.sendSuccess(() -> Component.empty().append(entity.getDisplayName()).append(" " + report),
                false /* broadcastToOps */);
        return 1;
    }

    static String describe(BodySnapshot body) {
        // 部位名称和上限取自这具身体的当前图纸，尸丹库存按各属性合计。
        var text = new StringBuilder(body.getTemplate().getId());
        var structure = body.getStructure();
        var hp = body.getPartStatesList().stream().collect(Collectors.toMap(PartState::getId, PartState::getHp));
        for (var part : structure.getPartsList()) {
            text.append(String.format(Locale.ROOT, "\n%s %.2f / %.2f", part.getDisplayName(),
                    hp.get(part.getId()), part.getMaxHp()));
        }
        var pearl = body.getOrganStatesList().stream().filter(organ -> organ.getId().equals("corpse_pearl"))
                .findFirst().orElseThrow();
        double qi = BodyProtobuf.fromInventory(pearl.getBuffer()).totalQi();
        return text.append(String.format(Locale.ROOT, "\n尸丹 HP %.2f，存元 %.2f", pearl.getHp(), qi)).toString();
    }
}
