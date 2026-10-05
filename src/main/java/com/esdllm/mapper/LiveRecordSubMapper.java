package com.esdllm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.esdllm.model.LiveRecordSub;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 针对表【live_record_sub】的数据库操作 Mapper。
 *
 * @Entity com.esdllm.model.LiveRecordSub
 */
public interface LiveRecordSubMapper extends BaseMapper<LiveRecordSub> {

    /**
     * 某个群<b>曾经订阅过</b>的全部房间号（去重）—— 录播网页的可见性就靠它。
     *
     * <p><b>为什么用自定义 SQL 而不是别的写法</b>：
     * <ul>
     *   <li>{@code selectList(...)} 拿不到这个结果 —— 可见性只需要"房间号"这一列，
     *       而那种写法要把整行拉回来再在内存里去重；</li>
     *   <li>更要紧的是：<b>这条 SQL 要连逻辑删除的行一起算</b>。
     *       本项目的「录播取消订阅」是逻辑删除，而它上线时的承诺就是
     *       "已经录好的文件都留着"（见 LiveRecordPlugins#subDel 的回复文案）。
     *       如果可见性只认 <b>is_delete = 0</b>，那么取消订阅会让那个房间录出来的
     *       几十 GB 在网页上<b>凭空消失</b> —— 用户的第一反应必然是"文件被删了"。
     *       所以这里刻意要"曾订阅过就算"，于是不能走 MyBatis-Plus 的自动过滤。</li>
     * </ul>
     *
     * <p>✅ <b>自定义 {@code @Select} 不受 MyBatis-Plus 全局逻辑删除影响</b>：
     * 那套 {@code is_delete = 0} 是注入到它自己生成的语句里的，
     * 注解里手写的 SQL 原样下发。这条方法正依赖这一点 —— 靠它"绕过"过滤，
     * 所以别把它改写成 {@code selectList}，也别指望加上别的前缀就还保有这个语义。
     *
     * @param groupId 群号（{@code null} 时调用方应直接返回空集 —— SQL 里
     *                {@code group_id = NULL} 永远不成立，那是数据库的三值逻辑，不是 bug）
     * @return 房间号列表（可能为空，不会是 {@code null}）
     */
    @Select("SELECT DISTINCT room_id FROM live_record_sub "
            + "WHERE group_id = #{groupId} AND room_id IS NOT NULL")
    List<Long> selectEverySubscribedRoomIds(@Param("groupId") Long groupId);
}
