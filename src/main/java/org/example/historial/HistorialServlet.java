package org.example.historial;

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

// Modelo actualizado para una transacción del historial
class Transaccion {
    private String tipo;
    private String codigo;
    private String fecha;
    private double total;
    private String detalle;
    private String estado; // Nuevo campo para mostrar si está anulada

    public Transaccion(String tipo, String codigo, String fecha, double total, String detalle, String estado) {
        this.tipo = tipo;
        this.codigo = codigo;
        this.fecha = fecha;
        this.total = total;
        this.detalle = detalle;
        this.estado = estado;
    }

    public String getFecha() {
        return fecha;
    }
}

@WebServlet("/historial")
public class HistorialServlet extends HttpServlet {
    private final Gson gson = new Gson();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        List<Transaccion> historial = new ArrayList<>();

        try (Connection conn = DatabaseConnection.getConnection()) {
            // 1. Obtenemos todas las VENTAS y su estado
            String sqlVentas = "SELECT v.codigo_venta, v.fecha_venta, v.total, v.estado, c.nombre as cliente_nombre " +
                    "FROM ventas v LEFT JOIN clientes c ON v.cliente_id = c.id";
            try (PreparedStatement stmt = conn.prepareStatement(sqlVentas);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String cliente = rs.getString("cliente_nombre");
                    historial.add(new Transaccion(
                            "Venta",
                            rs.getString("codigo_venta"),
                            rs.getTimestamp("fecha_venta").toString(),
                            rs.getDouble("total"),
                            (cliente != null) ? cliente : "Cliente Anónimo",
                            rs.getString("estado") // Añadimos el estado
                    ));
                }
            }

            // 2. Obtenemos todas las COMPRAS y su estado
            String sqlCompras = "SELECT c.codigo_compra, c.fecha_compra, c.total, c.estado, p.nombre as proveedor_nombre " +
                    "FROM compras c JOIN proveedores p ON c.proveedor_id = p.id";
            try (PreparedStatement stmt = conn.prepareStatement(sqlCompras);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    historial.add(new Transaccion(
                            "Compra",
                            rs.getString("codigo_compra"),
                            rs.getTimestamp("fecha_compra").toString(),
                            rs.getDouble("total"),
                            rs.getString("proveedor_nombre"),
                            rs.getString("estado") // Añadimos el estado
                    ));
                }
            }

            // 3. Obtenemos todas las DEVOLUCIONES
            String sqlDevoluciones = "SELECT codigo_devolucion, fecha_devolucion, codigo_transaccion_original FROM devoluciones";
            try (PreparedStatement stmt = conn.prepareStatement(sqlDevoluciones);
                 ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    historial.add(new Transaccion(
                            "Devolución",
                            rs.getString("codigo_devolucion"),
                            rs.getTimestamp("fecha_devolucion").toString(),
                            0.00, // Las devoluciones son una acción, no tienen total
                            "Anulación de: " + rs.getString("codigo_transaccion_original"),
                            "Procesada" // Estado para las devoluciones
                    ));
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"status\":\"error\", \"message\":\"Error al cargar el historial.\"}");
            return;
        }

        // Ordenamos la lista combinada por fecha para que todo aparezca cronológicamente
        historial.sort((t1, t2) -> t2.getFecha().compareTo(t1.getFecha()));

        response.getWriter().write(gson.toJson(historial));
    }
}