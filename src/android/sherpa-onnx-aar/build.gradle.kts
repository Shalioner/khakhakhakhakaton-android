// Обёртка над готовым sherpa-onnx AAR (Apache-2.0), чтобы library-модули могли на него ссылаться.
configurations.maybeCreate("default")
artifacts.add("default", file("../libs/sherpa-onnx-2.0.1.aar"))
