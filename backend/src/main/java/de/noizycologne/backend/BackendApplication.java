package de.noizycologne.backend;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {

	public static void main(String @NonNull [] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
