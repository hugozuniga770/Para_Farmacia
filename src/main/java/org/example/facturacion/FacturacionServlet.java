package org.example.facturacion;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.example.DatabaseConnection;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Type;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@WebServlet("/facturacion")
public class FacturacionServlet extends HttpServlet {

    // --- MODELOS DE DATOS (DEFINIDOS AQUÍ DENTRO) ---
    // NOTA: El ajuste clave es hacer estas clases 'static'.
    // Esto ayuda al compilador a encontrarlas sin problemas.

    static class ProductoVenta {
        String nombre;
        int cantidad;
        double precioUnitario;
        double subtotal;
    }

    static class VentaParaFacturar {
        int ventaId;
        String codigoVenta;
        String fechaVenta;
        String clienteNombre;
        String clienteNit;
        List<ProductoVenta> productos;
    }

    static class ItemAdicional {
        String descripcion;
        int cantidad;
        double montoUnitario;
        String tipo; // "ADICION" o "DEDUCCION"
    }

    static class FacturaRequest {
        int ventaId;
        double tasaImpuesto;
        double descuentoGlobal;
        List<ItemAdicional> itemsAdicionales;
    }
    // --- FIN DE LOS MODELOS ---

    private final Gson gson = new Gson();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String codigoVenta = request.getParameter("codigo");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        VentaParaFacturar ventaParaFacturar = new VentaParaFacturar();

        String sqlVenta = "SELECT v.id, v.codigo_venta, v.fecha_venta, c.nombre, c.nit " +
                "FROM ventas v " +
                "JOIN clientes c ON v.cliente_id = c.id " +
                "WHERE v.codigo_venta = ? AND v.estado = 'Completada' AND v.estado_facturacion = 'Pendiente'";

        String sqlProductos = "SELECT p.nombre, dv.cantidad, dv.precio_unitario, dv.subtotal " +
                "FROM detalle_ventas dv " +
                "JOIN productos p ON dv.producto_id = p.id " +
                "WHERE dv.venta_id = ?";

        try (Connection conn = DatabaseConnection.getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(sqlVenta)) {
                stmt.setString(1, codigoVenta);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        ventaParaFacturar.ventaId = rs.getInt("id");
                        ventaParaFacturar.codigoVenta = rs.getString("codigo_venta");
                        ventaParaFacturar.fechaVenta = rs.getTimestamp("fecha_venta").toString();
                        ventaParaFacturar.clienteNombre = rs.getString("nombre");
                        ventaParaFacturar.clienteNit = rs.getString("nit");
                    } else {
                        response.setStatus(404);
                        response.getWriter().write("{\"status\":\"error\", \"message\":\"Venta no encontrada, ya fue facturada o está anulada.\"}");
                        return;
                    }
                }
            }

            List<ProductoVenta> productos = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(sqlProductos)) {
                stmt.setInt(1, ventaParaFacturar.ventaId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while(rs.next()) {
                        ProductoVenta p = new ProductoVenta();
                        p.nombre = rs.getString("nombre");
                        p.cantidad = rs.getInt("cantidad");
                        p.precioUnitario = rs.getDouble("precio_unitario");
                        p.subtotal = rs.getDouble("subtotal");
                        productos.add(p);
                    }
                }
            }
            ventaParaFacturar.productos = productos;

            response.getWriter().write(gson.toJson(ventaParaFacturar));

        } catch (SQLException e) {
            e.printStackTrace();
            response.setStatus(500);
            response.getWriter().write("{\"status\":\"error\", \"message\":\"Error en la base de datos.\"}");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        Type type = new TypeToken<FacturaRequest>() {}.getType();
        FacturaRequest factReq = gson.fromJson(request.getReader(), type);

        Connection conn = null;
        String codigoFactura = "";
        try {
            conn = DatabaseConnection.getConnection();
            conn.setAutoCommit(false);

            double subtotalProductos = obtenerSubtotalProductos(conn, factReq.ventaId);
            double totalAdicionales = factReq.itemsAdicionales.stream()
                    .mapToDouble(item -> item.tipo.equals("ADICION") ? item.cantidad * item.montoUnitario : -item.cantidad * item.montoUnitario)
                    .sum();

            double baseImponible = subtotalProductos + totalAdicionales - factReq.descuentoGlobal;
            double montoImpuestos = baseImponible * (factReq.tasaImpuesto / 100.0);
            double totalFinal = baseImponible + montoImpuestos;

            String sqlFactura = "INSERT INTO facturas (venta_id, subtotal_productos, total_adicionales, descuento_global, tasa_impuesto, monto_impuestos, total_final) VALUES (?, ?, ?, ?, ?, ?, ?)";
            long facturaId;
            try (PreparedStatement stmt = conn.prepareStatement(sqlFactura, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setInt(1, factReq.ventaId);
                stmt.setDouble(2, subtotalProductos);
                stmt.setDouble(3, totalAdicionales);
                stmt.setDouble(4, factReq.descuentoGlobal);
                stmt.setDouble(5, factReq.tasaImpuesto);
                stmt.setDouble(6, montoImpuestos);
                stmt.setDouble(7, totalFinal);
                stmt.executeUpdate();
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) facturaId = rs.getLong(1);
                    else throw new SQLException("No se pudo crear la factura.");
                }
            }

            codigoFactura = "FACT-" + facturaId;
            try(PreparedStatement stmt = conn.prepareStatement("UPDATE facturas SET codigo_factura = ? WHERE id = ?")) {
                stmt.setString(1, codigoFactura);
                stmt.setLong(2, facturaId);
                stmt.executeUpdate();
            }

            if (factReq.itemsAdicionales != null && !factReq.itemsAdicionales.isEmpty()) {
                String sqlItems = "INSERT INTO factura_items_adicionales (factura_id, descripcion, cantidad, monto_unitario, tipo) VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement stmt = conn.prepareStatement(sqlItems)) {
                    for (ItemAdicional item : factReq.itemsAdicionales) {
                        stmt.setLong(1, facturaId);
                        stmt.setString(2, item.descripcion);
                        stmt.setInt(3, item.cantidad);
                        stmt.setDouble(4, item.montoUnitario);
                        stmt.setString(5, item.tipo);
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            }

            String sqlUpdateVenta = "UPDATE ventas SET estado_facturacion = 'Facturada' WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sqlUpdateVenta)) {
                stmt.setInt(1, factReq.ventaId);
                stmt.executeUpdate();
            }

            conn.commit();
            response.getWriter().write("{\"status\":\"success\", \"message\":\"Factura " + codigoFactura + " generada exitosamente.\"}");

        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            e.printStackTrace();
            response.setStatus(500);
            response.getWriter().write("{\"status\":\"error\", \"message\":\"" + e.getMessage() + "\"}");
        } finally {
            if (conn != null) try { conn.setAutoCommit(true); conn.close(); } catch (SQLException e) { e.printStackTrace(); }
        }
    }

    private double obtenerSubtotalProductos(Connection conn, int ventaId) throws SQLException {
        String sql = "SELECT SUM(subtotal) as total_productos FROM detalle_ventas WHERE venta_id = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, ventaId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getDouble("total_productos");
                return 0.0;
            }
        }
    }
}