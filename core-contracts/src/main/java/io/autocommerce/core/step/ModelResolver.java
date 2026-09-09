package io.autocommerce.core.step;

/**
 * 模型解析点 seam（specs/0006 §4 ModelResolver）。model_requirement → (provider, model) 选择器。
 * v1 实现 = 静态配置映射（切换 base_url/model 即全局换模型）；未来编排器 = 在同一接口实现路由
 * （按 Step 类型/成本/质量/可用性分派），core 契约与 Step 声明不改。
 */
public interface ModelResolver {

    ResolvedModel resolve(ModelRequirement requirement, StepContext context);
}
