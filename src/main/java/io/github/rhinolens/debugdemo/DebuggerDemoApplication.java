package io.github.rhinolens.debugdemo;

import org.springframework.boot.SpringApplication;
import io.github.rhinolens.lens.Lens;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DebuggerDemoApplication {

    public static void main(String[] args) {
        Lens.install(); // observe what scripts do to Java objects (batik-js-lens)
        SpringApplication.run(DebuggerDemoApplication.class, args);
    }
}
