package com.school.forum.forum.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 板块出参。
 *
 * @param status 0正常 1隐藏 2关闭（禁止发帖）。
 *               发帖页据此把已关闭的板块置灰——服务端当然还会再校验一次，
 *               但让用户选完才被拒绝，体验上说不过去
 */
public record BoardVO(Long id,
                      String name,
                      String code,
                      String description,
                      String icon,
                      long postCount,
                      long todayCount,
                      int status,
                      @JsonProperty("isDefault") boolean isDefault) {
}
