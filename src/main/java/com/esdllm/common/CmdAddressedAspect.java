package com.esdllm.common;

import com.esdllm.config.LoadDSConfig;
import com.mikuac.shiro.annotation.MessageHandlerFilter;
import com.mikuac.shiro.core.Bot;
import com.mikuac.shiro.dto.event.message.GroupMessageEvent;
import com.mikuac.shiro.dto.event.message.MessageEvent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * 命令入口闸：带 {@code cmd} 的 handler 在执行前先问一句「这条消息是<b>在跟机器人说</b>吗」。
 *
 * <p><b>为什么需要它 / 为什么不放进正则</b>：注解里的 {@code cmd} 必须是<b>编译期常量</b>，
 * 而「机器人叫什么」是运行时数据（换号、改昵称、改群名片都会变）。
 * 硬把昵称写进正则就是「换个号就作废」，所以正则只负责<b>语法</b>
 * （命令词前面可以有 {@code @某人}，见 {@link CmdPrefix#PREFIX}），
 * 身份判定挪到这里、放到 <b>handler 入口</b>统一做。
 *
 * <p><b>覆盖面</b>：切面按注解切（{@code @annotation(MessageHandlerFilter)}），
 * 只对<b>写了 {@code cmd} 的</b> handler 生效 ——
 * 链接自动解析、入站反刷屏（{@code AntiSpamPlugins} 的入站钩子不吃 {@code cmd}）
 * 这些跟"命令"无关的路径<b>一个字节都不受影响</b>。
 *
 * <p><b>为什么 Spring AOP 代理 {@code @Shiro} bean 不会影响 shiro 找 handler</b>：
 * shiro 的 {@code BotFactory.extractMethods} 用 {@code AopProxyUtils.ultimateTargetClass(obj)}
 * 取<b>终极目标类</b>再扫注解，所以注解照样能被发现；而它最终是
 * {@code method.getMethod().invoke(method.getObject(), args)}（反射 + 虚方法分派），
 * 代理类重写了同名方法 ⇒ 切面正常触发。Spring Boot 默认
 * {@code proxyTargetClass=true}（CGLIB），本项目的插件类既非 final、方法也非 final。
 *
 * <p><b>两条纪律</b>：
 * <ul>
 *   <li><b>fail-open</b>：切面自身任何异常（含拿不到参数）都<b>放行</b> ——
 *       这道闸只会"少拦"，绝不会因为自身出错而让命令集体失灵；</li>
 *   <li><b>可关</b>：配置键 {@code cmdAtOnly}（默认开）。关掉就退回 2.0.4 的宽松行为。</li>
 * </ul>
 *
 * <p>被拦下来的消息打一行 INFO（截断 120 字）：这正是 2.0.4 最缺的那条线索 ——
 * 当时 {@code @Hana 搜视频 xxx} 因为正则不匹配，handler 压根没进，
 * 日志里<b>连失败都没有</b>，只能靠人肉复现才定位到。
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CmdAddressedAspect {

    /** 日志里回显原文的长度上限（消息可能很长）。 */
    private static final int LOG_TEXT_MAX = 120;

    @Resource
    private LoadDSConfig loadDSConfig;

    /**
     * 入口切面：{@code @annotation(…) <b>写全限定类名</b>}。
     *
     * <p>⚠️ <b>不要改成 {@code @annotation(filter)} 这种"绑定到通知参数"的写法</b>：
     * 那种写法要求 AspectJ 知道通知方法的形参名（Spring 得先把
     * {@code setParameterNames} 喂进去），否则会被当成"有个叫 filter 的注解类型"
     * 直接解析失败 —— 探针 {@code temp/ProbeCmdPrefixAll} 第 9 节就是用
     * {@code AspectJExpressionPointcut} 真解析一遍这条表达式来守它的。
     * 注解对象改从 join point 的 {@link MethodSignature} 上取，效果一样、还更稳。
     */
    @Around("@annotation(com.mikuac.shiro.annotation.MessageHandlerFilter)")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        try {
            if (shouldCheck(resolveFilter(pjp)) && !addressedToSelf(pjp)) {
                return null;        // 命令 handler 都是 void；返回 null = 跳过、不阻断后续 handler
            }
        } catch (Exception e) {
            // fail-open 铁律：闸门自己坏了就放行，绝不误伤
            log.warn("命令 @机器人 判定异常（已放行）", e);
        }
        return pjp.proceed();
    }

    /**
     * 取当前 join point 方法上的 {@link MessageHandlerFilter}。
     *
     * <p>点切是按注解切的，所以注解<b>一定</b>存在；这里只是把它捞出来读 {@code cmd}。
     * 先看 join point 报的方法，取不到再沿"最具体方法"（CGLIB 代理时可能是父类方法）找一遍。
     */
    private static MessageHandlerFilter resolveFilter(ProceedingJoinPoint pjp) {
        if (!(pjp.getSignature() instanceof MethodSignature ms)) {
            return null;
        }
        Method method = ms.getMethod();
        MessageHandlerFilter filter = method.getAnnotation(MessageHandlerFilter.class);
        if (filter != null) {
            return filter;
        }
        try {
            Object target = pjp.getTarget();
            if (target != null) {
                return AopUtils.getMostSpecificMethod(method, target.getClass())
                        .getAnnotation(MessageHandlerFilter.class);
            }
        } catch (Exception e) {
            log.debug("定位 @MessageHandlerFilter 失败", e);
        }
        return null;
    }

    /** 只有"真命令"（写了 {@code cmd}）且总闸开着时才判定。 */
    private boolean shouldCheck(MessageHandlerFilter filter) {
        if (filter == null || filter.cmd() == null || filter.cmd().isBlank()) {
            return false;
        }
        return loadDSConfig.isEnabled(LoadDSConfig.KEY_CMD_AT_ONLY, true);
    }

    /** 从参数里捞出 {@code Bot}/{@code MessageEvent}，交给 {@link CmdPrefix#isAddressedToSelf} 判。 */
    private boolean addressedToSelf(ProceedingJoinPoint pjp) {
        Object[] args = pjp.getArgs();
        if (args == null) {
            return true;
        }
        Long selfId = null;
        MessageEvent event = null;
        for (Object arg : args) {
            if (arg instanceof Bot bot) {
                selfId = bot.getSelfId();
            } else if (arg instanceof MessageEvent me) {
                event = me;
            }
        }
        if (event == null) {
            return true;                    // 参数形状不认识 ⇒ 不拦
        }
        String message = event.getMessage();
        if (message == null) {
            return true;
        }
        if (selfId == null) {
            selfId = event.getSelfId();
        }
        Long groupId = event instanceof GroupMessageEvent g ? g.getGroupId() : null;
        boolean ok = CmdPrefix.isAddressedToSelf(selfId, groupId, message);
        if (!ok) {
            log.info("命令被拦：消息前置 @ 的不是机器人（selfId={}, group={}）｜{}",
                    selfId, groupId, truncate(message));
        }
        return ok;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= LOG_TEXT_MAX ? s : s.substring(0, LOG_TEXT_MAX) + "…";
    }
}
