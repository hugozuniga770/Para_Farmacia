package com.farmacia;

import org.apache.catalina.startup.Tomcat;
import java.io.File;
import java.util.Optional;

public class Main {
    public static void main(String[] args) throws Exception {
        Tomcat tomcat = new Tomcat();

        String portStr = Optional.ofNullable(System.getenv("PORT")).orElse("8080");
        int port = Integer.parseInt(portStr);
        tomcat.setPort(port);
        tomcat.getConnector(); // Necesario para inicializar el conector

        // Apuntar a los recursos web (JSPs, CSS, etc.)
        // Esto asume que están en src/main/webapp
        String webappDirLocation = "src/main/webapp/";
        tomcat.addWebapp("", new File(webappDirLocation).getAbsolutePath());
        System.out.println("Configuring app with basedir: " + new File(webappDirLocation).getAbsolutePath());

        tomcat.start();
        tomcat.getServer().await();
    }
}