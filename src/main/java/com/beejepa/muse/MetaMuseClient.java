package com.beejepa.muse;

import com.beejepa.muse.dto.MuseInferenceRequest;
import com.beejepa.muse.dto.MuseInferenceResponse;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.client.annotation.Client;

@Client("${meta.muse.base-url}")
public interface MetaMuseClient {

    @io.micronaut.http.annotation.Get("/models")
    HttpResponse<String> listModels(
            @Header("Authorization") String authorization
    );

    @Post("/responses")
    HttpResponse<String> createResponse(
            @Header("Authorization") String authorization,
            @Body String requestJson
    );

    @Post("/chat/completions")
    HttpResponse<String> chatCompletions(
            @Header("Authorization") String authorization,
            @Body String requestJson
    );

    @Post("/v1/models/muse/generate")
    HttpResponse<MuseInferenceResponse> infer(
            @Header("Authorization") String authorization,
            @Body MuseInferenceRequest request
    );

    @Post("/v1/models/muse/embeddings")
    HttpResponse<MuseInferenceResponse> embed(
            @Header("Authorization") String authorization,
            @Body MuseInferenceRequest request
    );
}
