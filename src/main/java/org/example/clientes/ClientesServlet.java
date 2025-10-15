package org.example.clientes;

import com.google.gson.Gson;
import org.example.DatabaseConnection;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

// --- 1. MODELO DE DATOS ACTUALIZADO ---
// Refleja la nueva estructura de la tabla 'clientes'.
class Cliente {
    int id;
    String nombre;
    String nit;
    String telefono;
    String email;
    String direccion;
}
// --- FIN DEL MODELO ---

@WebServlet("/clientes")
public class ClientesServlet extends HttpServlet {
    private final Gson gson = new Gson();

    // --- 2. MÉTODO GET ACTUALIZADO ---
    // Ahora obtiene todos los nuevos campos de la base de datos.
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        List<Cliente> clientes = new ArrayList<>();
        // La consulta SQL ahora pide todos los campos necesarios.
        String sql = "SELECT id, nombre, nit, telefono, email, direccion FROM clientes ORDER BY nombre ASC";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                Cliente c = new Cliente();
                c.id = rs.getInt("id");
                c.nombre = rs.getString("nombre");
                c.nit = rs.getString("nit");
                c.telefono = rs.getString("telefono");
                c.email = rs.getString("email");
                c.direccion = rs.getString("direccion");
                clientes.add(c);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"status\":\"error\", \"message\":\"Error al cargar los clientes.\"}");
            return;
        }
        response.getWriter().write(gson.toJson(clientes));
    }

    // --- 3. MÉTODO POST ACTUALIZADO ---
    // Para registrar nuevos clientes con los nuevos campos.
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        request.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        // Obtenemos los nuevos parámetros del formulario
        String nombre = request.getParameter("nombre");
        String nit = request.getParameter("nit");
        String telefono = request.getParameter("telefono");
        String email = request.getParameter("email");
        String direccion = request.getParameter("direccion");

        String sql = "INSERT INTO clientes (nombre, nit, telefono, email, direccion) VALUES (?, ?, ?, ?, ?)";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, nombre);
            stmt.setString(2, nit);
            stmt.setString(3, telefono);
            stmt.setString(4, email);
            stmt.setString(5, direccion);

            stmt.executeUpdate();
            response.getWriter().write("{\"status\":\"success\", \"message\":\"Cliente registrado exitosamente\"}");

        } catch (SQLException e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"status\":\"error\", \"message\":\"Error en la base de datos.\"}");
        }
    }


    // --- 4. NUEVO MÉTODO PUT PARA ACTUALIZAR ---
    // Se encarga de recibir los datos editados desde la tabla.
    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String body = request.getReader().lines().collect(Collectors.joining(System.lineSeparator()));
        Cliente cliente = gson.fromJson(body, Cliente.class);

        // --- VALIDACIONES ---
        // Validamos que no haya datos duplicados antes de actualizar.
        String sqlCheck = "SELECT id FROM clientes WHERE (telefono = ? OR email = ?) AND id != ?";
        String sqlUpdate = "UPDATE clientes SET nombre = ?, nit = ?, telefono = ?, email = ?, direccion = ? WHERE id = ?";

        try (Connection conn = DatabaseConnection.getConnection()) {
            // Verificamos si el teléfono o email ya existen para OTRO cliente
            try (PreparedStatement stmtCheck = conn.prepareStatement(sqlCheck)) {
                stmtCheck.setString(1, cliente.telefono);
                stmtCheck.setString(2, cliente.email);
                stmtCheck.setInt(3, cliente.id);
                try (ResultSet rs = stmtCheck.executeQuery()) {
                    if (rs.next()) {
                        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        response.getWriter().write("{\"status\":\"error\", \"message\":\"El teléfono o email ya está registrado para otro cliente.\"}");
                        return;
                    }
                }
            }

            // Si no hay duplicados, procedemos a actualizar
            try (PreparedStatement stmtUpdate = conn.prepareStatement(sqlUpdate)) {
                stmtUpdate.setString(1, cliente.nombre);
                stmtUpdate.setString(2, cliente.nit);
                stmtUpdate.setString(3, cliente.telefono);
                stmtUpdate.setString(4, cliente.email);
                stmtUpdate.setString(5, cliente.direccion);
                stmtUpdate.setInt(6, cliente.id);

                int filasAfectadas = stmtUpdate.executeUpdate();
                if (filasAfectadas > 0) {
                    response.getWriter().write("{\"status\":\"success\", \"message\":\"Cliente actualizado.\"}");
                } else {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    response.getWriter().write("{\"status\":\"error\", \"message\":\"No se encontró el cliente.\"}");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"status\":\"error\", \"message\":\"Error en la base de datos.\"}");
        }
    }
}