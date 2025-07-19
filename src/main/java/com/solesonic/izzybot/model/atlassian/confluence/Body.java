package com.solesonic.izzybot.model.atlassian.confluence;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Body{
    private Storage storage;

    @JsonProperty("atlas_doc_format")
    private AtlasDocFormat atlasDocFormat;


    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public AtlasDocFormat getAtlasDocFormat() {
        return atlasDocFormat;
    }

    public void setAtlasDocFormat(AtlasDocFormat atlasDocFormat) {
        this.atlasDocFormat = atlasDocFormat;
    }
}
