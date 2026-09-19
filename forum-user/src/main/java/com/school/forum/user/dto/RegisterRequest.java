package com.school.forum.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求。校验规则与《03-功能设计》1.1 节一致。
 *
 * <p>校验注解写在这里而不是散落在 Service 里：注解本身就是接口契约的一部分，
 * Swagger 会把它渲染成参数说明，前端也能照着它做即时校验。
 * Service 层只保留「用户名是否已被占用」这类必须查库才能判断的规则。
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{3,19}$",
            message = "用户名需 4-20 位，以字母开头，只能包含字母、数字和下划线")
    private String username;

    /**
     * 密码要求「必须同时含字母和数字」。
     * <p>不做特殊字符要求：那会显著提高输入成本，而校园论坛的价值不在防内部爆破，
     * 长度 + 字符类别已经足够把常见弱口令挡在门外。真正的防线是登录失败锁定。
     */
    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)\\S{8,32}$",
            message = "密码需 8-32 位，且同时包含字母和数字，不能有空格")
    private String password;

    @NotBlank(message = "昵称不能为空")
    @Pattern(regexp = "^[\\u4e00-\\u9fa5A-Za-z0-9_]{2,20}$",
            message = "昵称需 2-20 位，只能是中文、字母、数字和下划线")
    private String nickname;

    /** 学号可选。为空时不占用唯一索引（MySQL 唯一索引允许多行 NULL） */
    @Pattern(regexp = "^$|^\\d{8,20}$", message = "学号需为 8-20 位数字")
    private String studentNo;

    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱长度不能超过 128 位")
    private String email;

    @Size(max = 64, message = "学院名称不能超过 64 位")
    private String college;

    @Size(max = 64, message = "专业名称不能超过 64 位")
    private String major;
}
