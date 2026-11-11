package com.yeogidot.yeogidot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeogidot.yeogidot.entity.User;
import com.yeogidot.yeogidot.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;
import software.amazon.awssdk.services.s3.S3Client;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.config.location=classpath:context-test.properties")
@AutoConfigureMockMvc
class UndatedPhotoTravelIntegrationTest {
    @MockitoBean S3Client s3Client;
    @MockitoBean RestTemplate restTemplate;
    @MockitoBean StringRedisTemplate redisTemplate;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    private String email;

    @BeforeEach
    void createUser() {
        email = UUID.randomUUID() + "@example.invalid";
        users.save(User.create(email, "test-only"));
    }

    @Test
    void mixedPhotosKeepTheKnownTravelPeriodAndAllPhotos() throws Exception {
        JsonNode photos = upload("""
                [
                    {"originalName":"later.png","takenAt":"2026-04-05T10:00:00"},
                    {"originalName":"unknown.png","takenAt":null,"latitude":null,"longitude":null},
                    {"originalName":"earlier.png","takenAt":"2026-04-03T10:00:00"}
                ]
                """, "later.png", "unknown.png", "earlier.png");
        long later = photos.get(0).get("id").asLong();
        long undated = photos.get(1).get("id").asLong();
        long earlier = photos.get(2).get("id").asLong();
        long travelId = createTravel(List.of(later, undated, earlier), undated);

        mvc.perform(get("/api/travels/{id}", travelId).with(user(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").value("2026-04-03"))
                .andExpect(jsonPath("$.endDate").value("2026-04-05"))
                .andExpect(jsonPath("$.representativePhotoId").value(undated))
                .andExpect(jsonPath("$.days.length()").value(2))
                .andExpect(jsonPath("$.days[0].photos[*].photoId")
                        .value(containsInAnyOrder((int) earlier, (int) undated)))
                .andExpect(jsonPath("$.days[1].photos[0].photoId").value(later));

        mvc.perform(get("/api/photos/{id}", undated).with(user(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.takenAt").value(nullValue()))
                .andExpect(jsonPath("$.latitude").value(nullValue()))
                .andExpect(jsonPath("$.longitude").value(nullValue()));
    }

    @Test
    void allUndatedPhotosUseUploadDateAndCanBeRegroupedWhenADateIsAdded() throws Exception {
        JsonNode photos = upload("""
                [{"originalName":"first.png","takenAt":null},{"originalName":"second.png"}]
                """, "first.png", "second.png");
        long first = photos.get(0).get("id").asLong();
        long second = photos.get(1).get("id").asLong();
        String uploadDate = LocalDateTime.parse(photos.get(0).get("createdDate").asText())
                .toLocalDate().toString();
        long travelId = createTravel(List.of(first, second), first);

        mvc.perform(get("/api/travels").with(user(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].startDate").value(uploadDate))
                .andExpect(jsonPath("$[0].endDate").value(uploadDate));
        var detail = mvc.perform(get("/api/travels/{id}", travelId).with(user(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(1))
                .andExpect(jsonPath("$.days[0].date").value(uploadDate))
                .andExpect(jsonPath("$.days[0].photos.length()").value(2))
                .andReturn().getResponse();
        long dayId = json.readTree(detail.getContentAsByteArray()).at("/days/0/dayId").asLong();
        mvc.perform(post("/api/travels/days/{dayId}/logs", dayId).with(user(email))
                        .contentType("application/json").content("{\"content\":\"Before dating a photo\"}"))
                .andExpect(status().isCreated());

        mvc.perform(patch("/api/photos/{id}", first).with(user(email))
                        .contentType("application/json")
                        .content("{\"takenAt\":\"2025-01-02T12:00:00\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/travels/{id}", travelId).with(user(email))
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("photoIds", List.of(first, second)))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/travels/{id}", travelId).with(user(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").value("2025-01-02"))
                .andExpect(jsonPath("$.days.length()").value(1))
                .andExpect(jsonPath("$.days[0].diary.content").value("Before dating a photo"))
                .andExpect(jsonPath("$.days[0].photos[*].photoId")
                        .value(containsInAnyOrder((int) first, (int) second)));
    }

    private JsonNode upload(String metadata, String... filenames) throws Exception {
        var request = multipart("/api/photos/upload");
        try (var out = new ByteArrayOutputStream()) {
            ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
            for (String filename : filenames) {
                request.file(new MockMultipartFile("files", filename, "image/png", out.toByteArray()));
            }
        }
        var response = mvc.perform(request.param("metadata", metadata).with(user(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        return json.readTree(response.getContentAsByteArray()).get("uploadedPhotos");
    }

    private long createTravel(List<Long> photoIds, long representative) throws Exception {
        var response = mvc.perform(post("/api/travels").with(user(email)).contentType("application/json")
                        .content(json.writeValueAsString(Map.of(
                                "title", "Undated photo test",
                                "photoIds", photoIds,
                                "representativePhotoId", representative))))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        return Long.parseLong(response.getContentAsString());
    }
}
