package io.github.ycfeng.ocdeck.data.opencode

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelParsingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun extractModelsOnlyReturnsConnectedProviders() {
        val payload = json.parseToJsonElement(
            """
            {
              "all": {
                "provider-alpha": {
                  "id": "provider-alpha",
                  "name": "Provider Alpha",
                  "models": {
                    "model-standard": { "id": "model-standard", "name": "Model Standard" }
                  }
                },
                "provider-beta": {
                  "id": "provider-beta",
                  "name": "Provider Beta",
                  "models": {
                    "model-fast": { "id": "model-fast", "name": "Model Fast" }
                  }
                }
              },
              "default": {
                "provider-alpha": "model-standard"
              },
              "connected": ["provider-alpha"]
            }
            """.trimIndent(),
        )

        val models = payload.extractModels()
        val defaults = payload.extractServerDefaultModels(models)

        assertEquals(listOf("provider-alpha/model-standard"), models.map { "${it.providerId}/${it.modelId}" })
        assertTrue(models.all { it.isConnected })
        assertEquals(emptyList<String>(), models.first().variants)
        assertEquals(listOf("provider-alpha/model-standard"), defaults.map { "${it.providerId}/${it.modelId}" })
    }

    @Test
    fun extractModelsSupportsConnectedObjectKeys() {
        val payload = json.parseToJsonElement(
            """
            {
              "all": {
                "provider-alpha": {
                  "id": "provider-alpha",
                  "name": "Provider Alpha",
                  "models": {
                    "model-standard": { "name": "Model Standard" }
                  }
                },
                "provider-beta": {
                  "id": "provider-beta",
                  "name": "Provider Beta",
                  "models": {
                    "model-fast": { "name": "Model Fast" }
                  }
                }
              },
              "connected": {
                "provider-alpha": {}
              }
            }
            """.trimIndent(),
        )

        val models = payload.extractModels()

        assertEquals(1, models.size)
        assertEquals("provider-alpha", models.single().providerId)
        assertEquals("model-standard", models.single().modelId)
        assertEquals("Provider Alpha", models.single().providerName)
    }

    @Test
    fun extractModelsReadsDynamicVariantsPerModel() {
        val payload = json.parseToJsonElement(
            """
            {
              "all": {
                "provider-alpha": {
                  "id": "provider-alpha",
                  "name": "Provider Alpha",
                  "models": {
                    "model-standard": {
                      "name": "Model Standard",
                      "variants": {
                        "low": { "reasoningEffort": "low" },
                        "medium": { "reasoningEffort": "medium" },
                        "high": { "reasoningEffort": "high" },
                        "max": { "reasoningEffort": "max" }
                      }
                    },
                    "model-fast": {
                      "name": "Model Fast",
                      "variants": {
                        "none": { "reasoningEffort": "none" },
                        "high": { "reasoningEffort": "high" }
                      }
                    },
                    "model-basic": {
                      "name": "Model Basic",
                      "variants": {}
                    }
                  }
                }
              },
              "connected": ["provider-alpha"]
            }
            """.trimIndent(),
        )

        val models = payload.extractModels()

        assertEquals(
            listOf("low", "medium", "high", "max"),
            models.first { it.modelId == "model-standard" }.variants,
        )
        assertEquals(
            listOf("none", "high"),
            models.first { it.modelId == "model-fast" }.variants,
        )
        assertEquals(emptyList<String>(), models.first { it.modelId == "model-basic" }.variants)
    }

    @Test
    fun extractModelsReadsContextLimit() {
        val payload = json.parseToJsonElement(
            """
            {
              "all": {
                "provider-alpha": {
                  "id": "provider-alpha",
                  "name": "Provider Alpha",
                  "models": {
                    "model-fast": {
                      "id": "model-fast",
                      "name": "Model Fast",
                      "limit": {
                        "context": 100000,
                        "input": 75000,
                        "output": 25000
                      }
                    }
                  }
                }
              },
              "connected": ["provider-alpha"]
            }
            """.trimIndent(),
        )

        val model = payload.extractModels().single()

        assertEquals(100_000L, model.contextLimit)
    }

    @Test
    fun extractModelsHonorsBlacklistAndWhitelist() {
        val payload = json.parseToJsonElement(
            """
            {
              "all": {
                "provider-alpha": {
                  "id": "provider-alpha",
                  "name": "Provider Alpha",
                  "models": {
                    "model-standard": { "name": "Model Standard" },
                    "model-fast": { "name": "Model Fast" },
                    "model-extra": { "name": "Model Extra" }
                  }
                }
              },
              "default": {
                "provider-alpha": "model-fast"
              },
              "connected": ["provider-alpha"]
            }
            """.trimIndent(),
        )
        val config = json.parseToJsonElement(
            """
            {
              "provider": {
                "provider-alpha": {
                  "whitelist": ["model-standard", "model-fast"],
                  "blacklist": ["model-fast"]
                }
              }
            }
            """.trimIndent(),
        )

        val enabledModels = payload.extractModels(config)
        val allModels = payload.extractModels(config, includeHidden = true)

        assertEquals(listOf("model-standard"), enabledModels.map { it.modelId })
        assertFalse(allModels.first { it.modelId == "model-fast" }.isEnabled)
        assertFalse(allModels.first { it.modelId == "model-extra" }.isEnabled)
        assertTrue(payload.extractServerDefaultModels(enabledModels).isEmpty())
    }

    @Test
    fun extractModelsAppliesLocalHiddenModels() {
        val payload = json.parseToJsonElement(
            """
            {
              "all": {
                "provider-alpha": {
                  "id": "provider-alpha",
                  "name": "Provider Alpha",
                  "models": {
                    "model-standard": { "name": "Model Standard" },
                    "model-fast": { "name": "Model Fast" }
                  }
                }
              },
              "connected": ["provider-alpha"]
            }
            """.trimIndent(),
        )
        val hiddenModels = setOf("provider-alpha" to "model-fast")

        val enabledModels = payload.extractModels(hiddenModels = hiddenModels)
        val allModels = payload.extractModels(includeHidden = true, hiddenModels = hiddenModels)

        assertEquals(listOf("model-standard"), enabledModels.map { it.modelId })
        assertTrue(allModels.first { it.modelId == "model-standard" }.isEnabled)
        assertFalse(allModels.first { it.modelId == "model-fast" }.isEnabled)
    }

    @Test
    fun extractModelSettingsAppliesMultipleLocalHiddenModels() {
        val payload = json.parseToJsonElement(
            """
            {
              "all": {
                "provider-alpha": {
                  "id": "provider-alpha",
                  "name": "Provider Alpha",
                  "models": {
                    "model-compact": { "name": "Model Compact" },
                    "model-standard": { "name": "Model Standard" },
                    "model-fast": { "name": "Model Fast" }
                  }
                }
              },
              "connected": ["provider-alpha"]
            }
            """.trimIndent(),
        )
        val hiddenModels = setOf("provider-alpha" to "model-compact", "provider-alpha" to "model-standard")

        val models = payload.extractModelSettings(hiddenModels = hiddenModels).single().models

        assertFalse(models.first { it.modelId == "model-compact" }.isEnabled)
        assertFalse(models.first { it.modelId == "model-standard" }.isEnabled)
        assertTrue(models.first { it.modelId == "model-fast" }.isEnabled)
    }

    @Test
    fun extractModelSettingsAddsBlacklistedRowsMissingFromProviderPayload() {
        val payload = json.parseToJsonElement(
            """
            {
              "all": {
                "provider-alpha": {
                  "id": "provider-alpha",
                  "name": "Provider Alpha",
                  "models": {
                    "model-standard": { "name": "Model Standard" }
                  }
                }
              },
              "connected": ["provider-alpha"]
            }
            """.trimIndent(),
        )
        val config = json.parseToJsonElement(
            """
            {
              "provider": {
                "provider-alpha": {
                  "blacklist": ["model-fast"]
                }
              }
            }
            """.trimIndent(),
        )

        val groups = payload.extractModelSettings(config)
        val models = groups.single().models

        assertEquals(listOf("model-fast", "model-standard"), models.map { it.modelId }.sorted())
        assertFalse(models.first { it.modelId == "model-fast" }.isEnabled)
    }

}
