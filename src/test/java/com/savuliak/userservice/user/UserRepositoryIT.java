package com.savuliak.userservice.user;

import com.savuliak.userservice.support.PostgresContainerBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class UserRepositoryIT extends PostgresContainerBase {

    @Autowired
    private UserRepository userRepository;

    @Test
    void persistsAndReloadsUser_withJsonbRoundTrip() {
        UUID id = UUID.randomUUID();

        User saved = userRepository.saveAndFlush(
                User.builder()
                        .id(id)
                        .displayName("Jane")
                        .avatarUrl("https://example.com/a.png")
                        .settings(Map.of("theme", "dark", "lang", "en"))
                        .balance(new BigDecimal("12.34"))
                        .build());

        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        Optional<User> reloaded = userRepository.findById(id);
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().getSettings())
                .containsEntry("theme", "dark")
                .containsEntry("lang", "en");
        assertThat(reloaded.get().getBalance()).isEqualByComparingTo("12.34");
    }

    @Test
    void builderDefault_persistsEmptySettings_notNull() {
        UUID id = UUID.randomUUID();

        userRepository.saveAndFlush(User.builder().id(id).build());

        User reloaded = userRepository.findById(id).orElseThrow();
        assertThat(reloaded.getSettings()).isNotNull().isEmpty();
        assertThat(reloaded.getBalance()).isEqualByComparingTo("0.00");
    }
}
