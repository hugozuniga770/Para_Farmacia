package org.example.ventas;

import org.example.DatabaseConnection;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Type;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

// --- MODELOS DE DATOS (DEFINIDOS AQUÍ ARRIBA) ---

// Modelo para los productos que se muestran en el autocompletado de ventas.
// Ahora incluye el stock, como lo pediste.
class ProductoVenta {
    private int id;
    private String nombre;
    private double precioVenta;
    private int stock;

    public ProductoVenta(int id, String nombre, double precioVenta, int stock) {
        this.id = id;
        this.nombre = nombre;
        this.precioVenta = precioVenta;
        this.stock = stock;
    }
}

// Modelo para cada item dentro del carrito de la venta.
class VentaItem {
    int productoId;
    int cantidad;
    double precioUnitario;
}

// Modelo para recibir la solicitud completa de venta desde el frontend.
class VentaRequest {
    String clienteNombre;
    String clienteNit;
    String clienteTelefono;
    String clienteEmail;
    String clienteDireccion;
    List<VentaItem> items;
}
// --- FIN DE LOS MODELOS ---


@WebServlet("/ventas")
public class VentasServlet extends HttpServlet {
    private final Gson gson = new Gson();

    // El método GET se usa para cargar la lista de productos en la página de ventas.
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        List<ProductoVenta> productos = new ArrayList<>();
        // La consulta ahora trae el stock para mostrarlo en la lista desplegable.
        String sql = "SELECT id, nombre, precio_venta, stock FROM productos WHERE stock > 0 ORDER BY nombre ASC";

        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                productos.add(new ProductoVenta(
                        rs.getInt("id"),
                        rs.getString("nombre"),
                        rs.getDouble("precio_venta"),
                        rs.getInt("stock") // Incluimos el stock
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
            // Manejo de error si no se pueden cargar los productos
        }
        response.getWriter().write(gson.toJson(productos));
    }

    // El método POST se usa para registrar la venta.
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        Type type = new TypeToken<VentaRequest>() {}.getType();
        VentaRequest ventaReq = gson.fromJson(request.getReader(), type);

        Connection conn = null;
        try {
            conn = DatabaseConnection.getConnection();
            conn.setAutoCommit(false); // Usamos una transacción para asegurar que todo se guarde correctamente.

            long clienteId = obtenerOcrearClienteId(conn, ventaReq);

            String sqlVenta = "INSERT INTO ventas (cliente_id, total, estado, estado_facturacion) VALUES (?, ?, 'Completada', 'Pendiente')";
            long ventaId;
            double totalVenta = ventaReq.items.stream().mapToDouble(item -> item.cantidad * item.precioUnitario).sum();

            try (PreparedStatement stmtVenta = conn.prepareStatement(sqlVenta, Statement.RETURN_GENERATED_KEYS)) {
                stmtVenta.setLong(1, clienteId);
                stmtVenta.setDouble(2, totalVenta);
                stmtVenta.executeUpdate();
                try (ResultSet rs = stmtVenta.getGeneratedKeys()) {
                    if (rs.next()) {
                        ventaId = rs.getLong(1);
                    } else {
                        throw new SQLException("Error: No se pudo registrar la venta.");
                    }
                }
            }

            String codigoVenta = "VENTA-" + ventaId;
            try(PreparedStatement stmtUpdate = conn.prepareStatement("UPDATE ventas SET codigo_venta = ? WHERE id = ?")) {
                stmtUpdate.setString(1, codigoVenta);
                stmtUpdate.setLong(2, ventaId);
                stmtUpdate.executeUpdate();
            }

            String sqlDetalle = "INSERT INTO detalle_ventas (venta_id, producto_id, cantidad, precio_unitario, subtotal) VALUES (?, ?, ?, ?, ?)";
            String sqlUpdateStock = "UPDATE productos SET stock = stock - ? WHERE id = ?";

            try (PreparedStatement stmtDetalle = conn.prepareStatement(sqlDetalle);
                 PreparedStatement stmtStock = conn.prepareStatement(sqlUpdateStock)) {

                for (VentaItem item : ventaReq.items) {
                    stmtDetalle.setLong(1, ventaId);
                    stmtDetalle.setInt(2, item.productoId);
                    stmtDetalle.setInt(3, item.cantidad);
                    stmtDetalle.setDouble(4, item.precioUnitario);
                    stmtDetalle.setDouble(5, item.cantidad * item.precioUnitario);
                    stmtDetalle.addBatch();

                    stmtStock.setInt(1, item.cantidad);
                    stmtStock.setInt(2, item.productoId);
                    stmtStock.addBatch();
                }
                stmtDetalle.executeBatch();
                stmtStock.executeBatch();
            }

            conn.commit(); // Si todo sale bien, guardamos los cambios.
            response.getWriter().write("{\"status\":\"success\", \"message\":\"Venta " + codigoVenta + " registrada exitosamente.\"}");

        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write("{\"status\":\"error\", \"message\":\"" + e.getMessage() + "\"}");
        } finally {
            if (conn != null) try { conn.setAutoCommit(true); conn.close(); } catch (SQLException e) { e.printStackTrace(); }
        }
    }

    // Este es el método "inteligente" para buscar o crear clientes.
    private long obtenerOcrearClienteId(Connection conn, VentaRequest ventaReq) throws SQLException {
        String sqlBusqueda = "SELECT id FROM clientes WHERE (nit IS NOT NULL AND nit != '' AND nit = ?) OR (telefono IS NOT NULL AND telefono != '' AND telefono = ?) OR (email IS NOT NULL AND email != '' AND email = ?)";

        // Solo buscamos si el usuario proporcionó un dato único.
        if ((ventaReq.clienteNit != null && !ventaReq.clienteNit.trim().isEmpty()) ||
                (ventaReq.clienteTelefono != null && !ventaReq.clienteTelefono.trim().isEmpty()) ||
                (ventaReq.clienteEmail != null && !ventaReq.clienteEmail.trim().isEmpty())) {

            try (PreparedStatement stmt = conn.prepareStatement(sqlBusqueda)) {
                stmt.setString(1, ventaReq.clienteNit);
                stmt.setString(2, ventaReq.clienteTelefono);
                stmt.setString(3, ventaReq.clienteEmail);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong("id"); // Cliente encontrado.
                    }
                }
            }
        }

        // Si no se encontró (o no se buscaron datos únicos), creamos uno nuevo.
        String sqlCreacion = "INSERT INTO clientes (nombre, nit, telefono, email, direccion) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sqlCreacion, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, ventaReq.clienteNombre);
            stmt.setString(2, ventaReq.clienteNit);
            stmt.setString(3, ventaReq.clienteTelefono);
            stmt.setString(4, ventaReq.clienteEmail);
            stmt.setString(5, ventaReq.clienteDireccion);
            stmt.executeUpdate();

            try (ResultSet rs = stmt.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1); // Devolvemos el ID del nuevo cliente.
                } else {
                    throw new SQLException("Error: No se pudo crear el nuevo cliente.");
                }
            }
        }
    }
}