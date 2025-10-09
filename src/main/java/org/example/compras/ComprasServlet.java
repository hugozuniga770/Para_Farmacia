package org.example.compras;

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
import java.util.Map;

// (Los modelos de datos no cambian)
class ProveedorSimple { private int id; private String nombre; public ProveedorSimple(int id, String nombre) { this.id = id; this.nombre = nombre; }}
class CatalogoProducto { private int id; private String nombreProducto; private double costo; public CatalogoProducto(int id, String nombreProducto, double costo) { this.id = id; this.nombreProducto = nombreProducto; this.costo = costo; }}
class CompraItem { String nombreProducto; int cantidad; double costoUnitario; String descripcion; }

@WebServlet("/compras")
public class ComprasServlet extends HttpServlet {
    private final Gson gson = new Gson();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // Este método ya estaba correcto
        String proveedorIdParam = request.getParameter("proveedorId");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        if (proveedorIdParam != null) {
            int proveedorId = Integer.parseInt(proveedorIdParam);
            List<CatalogoProducto> catalogo = new ArrayList<>();
            String sql = "SELECT id, nombre_producto, costo FROM catalogo_proveedor WHERE proveedor_id = ?";
            try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, proveedorId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        catalogo.add(new CatalogoProducto(rs.getInt("id"), rs.getString("nombre_producto"), rs.getDouble("costo")));
                    }
                }
            } catch (SQLException e) { e.printStackTrace(); response.sendError(500); return; }
            response.getWriter().write(gson.toJson(catalogo));
        } else {
            List<ProveedorSimple> proveedores = new ArrayList<>();
            String sql = "SELECT id, nombre FROM proveedores";
            try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql); ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    proveedores.add(new ProveedorSimple(rs.getInt("id"), rs.getString("nombre")));
                }
            } catch (SQLException e) { e.printStackTrace(); response.sendError(500); return; }
            response.getWriter().write(gson.toJson(proveedores));
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // Leemos los datos que llegan
        Map<String, Object> payload = gson.fromJson(request.getReader(), Map.class);
        int proveedorId = ((Double) payload.get("proveedorId")).intValue();
        Type itemListType = new TypeToken<List<CompraItem>>() {}.getType();
        List<CompraItem> items = gson.fromJson(gson.toJson(payload.get("items")), itemListType);

        Connection conn = null;
        try {
            conn = DatabaseConnection.getConnection();
            conn.setAutoCommit(false);

            // ====> ¡CORRECCIÓN AÑADIDA AQUÍ! <====
            // Definimos el tipo de contenido y la codificación al principio.
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");

            long compraId;
            String codigoCompra = "";

            double totalCompra = items.stream().mapToDouble(item -> item.cantidad * item.costoUnitario).sum();
            String sqlCompra = "INSERT INTO compras (proveedor_id, total) VALUES (?, ?)";
            try (PreparedStatement stmt = conn.prepareStatement(sqlCompra, Statement.RETURN_GENERATED_KEYS)) {
                stmt.setInt(1, proveedorId);
                stmt.setDouble(2, totalCompra);
                stmt.executeUpdate();
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) compraId = rs.getLong(1);
                    else throw new SQLException("No se pudo obtener el ID de la compra.");
                }
            }

            codigoCompra = "COMPRA-" + compraId;
            String sqlUpdateCodigo = "UPDATE compras SET codigo_compra = ? WHERE id = ?";
            try(PreparedStatement stmtUpdate = conn.prepareStatement(sqlUpdateCodigo)) {
                stmtUpdate.setString(1, codigoCompra);
                stmtUpdate.setLong(2, compraId);
                stmtUpdate.executeUpdate();
            }

            String sqlDetalle = "INSERT INTO detalle_compras (compra_id, producto_id, cantidad, costo_unitario, subtotal) VALUES (?, ?, ?, ?, ?)";
            try (PreparedStatement stmtDetalle = conn.prepareStatement(sqlDetalle)) {
                for (CompraItem item : items) {
                    long productoId;
                    String sqlCheckProducto = "SELECT id FROM productos WHERE nombre = ?";
                    try (PreparedStatement stmtCheck = conn.prepareStatement(sqlCheckProducto)) {
                        stmtCheck.setString(1, item.nombreProducto);
                        try (ResultSet rs = stmtCheck.executeQuery()) {
                            if (rs.next()) {
                                productoId = rs.getLong("id");
                            } else {
                                String sqlAddProducto = "INSERT INTO productos (nombre, categoria, medida, precio_venta) VALUES (?, ?, ?, ?)";
                                try (PreparedStatement stmtAdd = conn.prepareStatement(sqlAddProducto, Statement.RETURN_GENERATED_KEYS)) {
                                    stmtAdd.setString(1, item.nombreProducto);
                                    stmtAdd.setString(2, "General");
                                    stmtAdd.setString(3, "Unidad");
                                    stmtAdd.setDouble(4, item.costoUnitario * 1.5);
                                    stmtAdd.executeUpdate();
                                    try (ResultSet rsAdd = stmtAdd.getGeneratedKeys()) {
                                        if (rsAdd.next()) {
                                            productoId = rsAdd.getLong(1);
                                            String codigoProducto = "PROD-" + productoId;
                                            String sqlUpdateProdCodigo = "UPDATE productos SET codigo = ? WHERE id = ?";
                                            try(PreparedStatement stmtUpdate = conn.prepareStatement(sqlUpdateProdCodigo)) {
                                                stmtUpdate.setString(1, codigoProducto);
                                                stmtUpdate.setLong(2, productoId);
                                                stmtUpdate.executeUpdate();
                                            }
                                        } else throw new SQLException("No se pudo crear el producto en el inventario.");
                                    }
                                }
                            }
                        }
                    }

                    String sqlUpdateStock = "UPDATE productos SET stock = stock + ? WHERE id = ?";
                    try (PreparedStatement stmtStock = conn.prepareStatement(sqlUpdateStock)) {
                        stmtStock.setInt(1, item.cantidad);
                        stmtStock.setLong(2, productoId);
                        stmtStock.executeUpdate();
                    }

                    stmtDetalle.setLong(1, compraId);
                    stmtDetalle.setLong(2, productoId);
                    stmtDetalle.setInt(3, item.cantidad);
                    stmtDetalle.setDouble(4, item.costoUnitario);
                    stmtDetalle.setDouble(5, item.cantidad * item.costoUnitario);
                    stmtDetalle.addBatch();
                }
                stmtDetalle.executeBatch();
            }
            conn.commit();
            response.getWriter().write("{\"status\":\"success\", \"message\":\"Compra " + codigoCompra + " registrada y stock actualizado.\"}");
        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            e.printStackTrace();
            response.setStatus(500);
            response.getWriter().write("{\"status\":\"error\", \"message\":\"Error en la base de datos.\"}");
        } finally {
            if (conn != null) try { conn.setAutoCommit(true); conn.close(); } catch (SQLException e) { e.printStackTrace(); }
        }
    }
}