package com.riccon.api.workflow.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 画布节点坐标。
 *
 * <p>坐标只用于前端布局，不参与执行；服务端只做数值校验并原样往返保存。坐标不是必填字段，
 * 旧版本或纯表单编辑的草稿可以没有坐标（ADR-0024 第 4.1 节）。
 *
 * @param x 横向坐标
 * @param y 纵向坐标
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowGraphPosition(double x, double y) {}
