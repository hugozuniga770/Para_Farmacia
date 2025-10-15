package org.example;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@WebServlet("/login")
public class LoginServlet extends HttpServlet {

    private AuthService authService;

    @Override
    public void init() {
        authService = new AuthService();
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String username = request.getParameter("username");
        String password = request.getParameter("password");

        // Preparamos la respuesta para que sea de tipo JSON
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        if (authService.autenticar(username, password)) {
            // Apunta a la nueva ubicación del menú
            response.sendRedirect("menu.html");
        } else {
            // Apunta a la nueva ubicación del index
            response.sendRedirect("error.html?error=true");
        }
    }
}