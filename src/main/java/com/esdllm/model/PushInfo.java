package com.esdllm.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.esdllm.config.CustomDateTypeHandler;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 推送表
 * @TableName push_info
 */
@TableName(value ="push_info")
@Data
@Accessors(chain = true)
public class PushInfo {
    /**
     * 推送id
     */
    @TableId(type = IdType.AUTO)
    private Long pid;

    /**
     * 直播房间号
     */
    private Long roomId;

    /**
     * 群号
     */
    private Long groupId;

    /**
     * qq号
     */
    private Long qqUid;

    /**
     * 是否at全体 0-否  1-是 默认0
     */
    private Integer atAll = 0;

    /**
     * at 列表
     */
    @TableField(typeHandler = CustomDateTypeHandler.class)
    private List<Long> atList = List.of(0L);

    /**
     * 开播状态，0-未开播  1-开播 3-轮播中
     */
    private Integer liveStatus = 0;

    /**
     * 开播时间戳
     */
    private Long liveTime = 0L;

    /**
     * 是否开启直播推送 0-否  1-是 默认1
     */
    private Integer livePush = 1;

    /**
     * 是否开启动态推送 0-否  1-是 默认1
     */
    private Integer dynamicPush = 1;

    /**
     * 创建时间戳
     */
    private Long createTime = System.currentTimeMillis();

    /**
     * 更新时间戳
     */
    private Long updateTime = System.currentTimeMillis();

    /**
     * 逻辑删除 0-否  1-是 默认0
     */
    private Integer isDelete = 0;

    @Override
    public boolean equals(Object that) {
        if (this == that) {
            return true;
        }
        if (that == null) {
            return false;
        }
        if (getClass() != that.getClass()) {
            return false;
        }
        PushInfo other = (PushInfo) that;
        return (this.getPid() == null ? other.getPid() == null : this.getPid().equals(other.getPid()))
            && (this.getRoomId() == null ? other.getRoomId() == null : this.getRoomId().equals(other.getRoomId()))
            && (this.getGroupId() == null ? other.getGroupId() == null : this.getGroupId().equals(other.getGroupId()))
            && (this.getQqUid() == null ? other.getQqUid() == null : this.getQqUid().equals(other.getQqUid()))
            && (this.getAtAll() == null ? other.getAtAll() == null : this.getAtAll().equals(other.getAtAll()))
            && (this.getAtList() == null ? other.getAtList() == null : this.getAtList().equals(other.getAtList()))
            && (this.getLiveStatus() == null ? other.getLiveStatus() == null : this.getLiveStatus().equals(other.getLiveStatus()))
            && (this.getLiveTime() == null ? other.getLiveTime() == null : this.getLiveTime().equals(other.getLiveTime()))
            && (this.getLivePush() == null ? other.getLivePush() == null : this.getLivePush().equals(other.getLivePush()))
            && (this.getDynamicPush() == null ? other.getDynamicPush() == null : this.getDynamicPush().equals(other.getDynamicPush()))
            && (this.getCreateTime() == null ? other.getCreateTime() == null : this.getCreateTime().equals(other.getCreateTime()))
            && (this.getUpdateTime() == null ? other.getUpdateTime() == null : this.getUpdateTime().equals(other.getUpdateTime()))
            && (this.getIsDelete() == null ? other.getIsDelete() == null : this.getIsDelete().equals(other.getIsDelete()));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((getPid() == null) ? 0 : getPid().hashCode());
        result = prime * result + ((getRoomId() == null) ? 0 : getRoomId().hashCode());
        result = prime * result + ((getGroupId() == null) ? 0 : getGroupId().hashCode());
        result = prime * result + ((getQqUid() == null) ? 0 : getQqUid().hashCode());
        result = prime * result + ((getAtAll() == null) ? 0 : getAtAll().hashCode());
        result = prime * result + ((getAtList() == null) ? 0 : getAtList().hashCode());
        result = prime * result + ((getLiveStatus() == null) ? 0 : getLiveStatus().hashCode());
        result = prime * result + ((getLiveTime() == null) ? 0 : getLiveTime().hashCode());
        result = prime * result + ((getLivePush() == null) ? 0 : getLivePush().hashCode());
        result = prime * result + ((getDynamicPush() == null) ? 0 : getDynamicPush().hashCode());
        result = prime * result + ((getCreateTime() == null) ? 0 : getCreateTime().hashCode());
        result = prime * result + ((getUpdateTime() == null) ? 0 : getUpdateTime().hashCode());
        result = prime * result + ((getIsDelete() == null) ? 0 : getIsDelete().hashCode());
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getSimpleName());
        sb.append(" [");
        sb.append("Hash = ").append(hashCode());
        sb.append(", pid=").append(pid);
        sb.append(", roomId=").append(roomId);
        sb.append(", groupId=").append(groupId);
        sb.append(", qqUid=").append(qqUid);
        sb.append(", atAll=").append(atAll);
        sb.append(", atList=").append(atList);
        sb.append(", liveStatus=").append(liveStatus);
        sb.append(", liveTime=").append(liveTime);
        sb.append(", livePush=").append(livePush);
        sb.append(", dynamicPush=").append(dynamicPush);
        sb.append(", createTime=").append(createTime);
        sb.append(", updateTime=").append(updateTime);
        sb.append(", isDelete=").append(isDelete);
        sb.append("]");
        return sb.toString();
    }
}