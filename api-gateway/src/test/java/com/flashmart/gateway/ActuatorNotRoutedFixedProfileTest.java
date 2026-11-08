package com.flashmart.gateway;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** {@link ActuatorNotRoutedTest} for the fallback profile {@code fixed}. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("fixed")
class ActuatorNotRoutedFixedProfileTest extends ActuatorNotRoutedTest {
}
