package com.yads.userservice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Basic unit test for UserServiceApplication.
 * Note: Full integration tests with database require TestContainers setup.
 */
class UserServiceApplicationTests {

	@Test
	void applicationClassExists() {
		// Verify that the main application class exists and can be instantiated
		UserServiceApplication app = new UserServiceApplication();
		assertNotNull(app, "UserServiceApplication should be instantiable");
	}

}
