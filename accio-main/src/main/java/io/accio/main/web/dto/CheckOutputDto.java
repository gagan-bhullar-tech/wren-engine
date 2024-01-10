/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.accio.main.web.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.accio.base.dto.Manifest;

public class CheckOutputDto
{
    public static CheckOutputDto ready(Manifest manifest)
    {
        return new CheckOutputDto(Status.READY, manifest);
    }

    public static CheckOutputDto prepare(Manifest manifest)
    {
        return new CheckOutputDto(Status.PREPARING, manifest);
    }

    public enum Status
    {
        READY,
        PREPARING
    }

    private final Status status;
    private final Manifest manifest;

    @JsonCreator
    public CheckOutputDto(
            @JsonProperty("systemStatus") Status status,
            @JsonProperty("manifest") Manifest manifest)
    {
        this.status = status;
        this.manifest = manifest;
    }

    @JsonProperty
    public Status getStatus()
    {
        return status;
    }

    @JsonProperty
    public Manifest getManifest()
    {
        return manifest;
    }
}
