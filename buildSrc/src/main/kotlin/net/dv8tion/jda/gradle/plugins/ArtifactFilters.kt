/*
 * Copyright 2015 Austin Keener, Michael Ritter, Florian Spieß, and the JDA contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.dv8tion.jda.gradle.plugins

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.provider.SetProperty

interface ArtifactFilters {
    val opusExclusions: SetProperty<String>
    val additionalAudioExclusions: SetProperty<String>
    val nettyExclusions: SetProperty<String>
}

fun ShadowJar.applyOpusExclusions(filters: ArtifactFilters) {
    dependencies {
        for (exclusion in filters.opusExclusions.get()) {
            exclude(dependency(exclusion))
        }
    }
}

fun ShadowJar.applyAudioExclusions(filters: ArtifactFilters) {
    applyOpusExclusions(filters)

    dependencies {
        for (exclusion in filters.additionalAudioExclusions.get()) {
            exclude(dependency(exclusion))
        }
    }
}

fun ShadowJar.applyNettyExclusions(filters: ArtifactFilters) {
    dependencies {
        for (exclusion in filters.nettyExclusions.get()) {
            exclude(dependency(exclusion))
        }
    }
}
