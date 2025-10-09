package org.example.productos;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.example.DatabaseConnection;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

// --- CLASE INTERNA PARA EL PRODUCTO ---
// Esta clase SÍ va dentro de este mismo archivo, antes del Servlet.
class ProductoInventario {
    // Es importante que los nombres de las variables aquí
    // coincidan con los del JavaScript y la base de datos.
    int id;
    String codigo;
    String nombre;
    double precioVenta;
    int stock;
    String ubicacion;
    String fechaVencimiento;
}
// --- FIN DE LA CLASE INTERNA ---


@WebServlet("/productos")
public class ProductosServlet extends HttpServlet {
    private final Gson gson = new GsonBuilder().setDateFormat("yyyy-MM-dd").create();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        List<ProductoInventario> inventario = new ArrayList<>();
        String sql = "SELECT id, codigo, nombre, precio_venta, stock, ubicacion, fecha_vencimiento FROM productos ORDER BY nombre ASC";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                // Creamos un nuevo objeto por cada fila de la base de datos
                ProductoInventario p = new ProductoInventario();
                p.id = rs.getInt("id");
                p.codigo = rs.getString("codigo");
                p.nombre = rs.getString("nombre");
                p.precioVenta = rs.getDouble("precio_venta");
                p.stock = rs.getInt("stock");
                p.ubicacion = rs.getString("ubicacion");
                java.sql.Date fechaSql = rs.getDate("fecha_vencimiento");
                p.fechaVencimiento = (fechaSql != null) ? fechaSql.toString() : null;
                inventario.add(p);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"status\":\"error\", \"message\":\"Error al conectar con la base de datos.\"}");
            return;
        }
        response.getWriter().write(gson.toJson(inventario));
    }

    @Override
    protected void doPut(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String body = request.getReader().lines().collect(Collectors.joining(System.lineSeparator()));
        ProductoInventario producto = gson.fromJson(body, ProductoInventario.class);

        if (producto.nombre == null || producto.nombre.trim().isEmpty() ||
                producto.ubicacion == null || producto.ubicacion.trim().isEmpty() ||
                producto.precioVenta <= 0) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write("{\"status\":\"error\", \"message\":\"Nombre, ubicación y precio no pueden estar vacíos.\"}");
            return;
        }

        String sqlUpdate = "UPDATE productos SET nombre = ?, precio_venta = ?, stock = ?, ubicacion = ?, fecha_vencimiento = ? WHERE id = ?";
        String sqlCheckNombre = "SELECT id FROM productos WHERE nombre = ? AND id != ?";

        try (Connection conn = DatabaseConnection.getConnection()) {
            try (PreparedStatement stmtCheck = conn.prepareStatement(sqlCheckNombre)) {
                stmtCheck.setString(1, producto.nombre);
                stmtCheck.setInt(2, producto.id);
                try (ResultSet rs = stmtCheck.executeQuery()) {
                    if (rs.next()) {
                        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        response.getWriter().write("{\"status\":\"error\", \"message\":\"Ya existe otro producto con ese nombre.\"}");
                        return;
                    }
                }
            }

            try (PreparedStatement stmtUpdate = conn.prepareStatement(sqlUpdate)) {
                stmtUpdate.setString(1, producto.nombre);
                stmtUpdate.setDouble(2, producto.precioVenta);
                stmtUpdate.setInt(3, producto.stock);
                stmtUpdate.setString(4, producto.ubicacion);

                java.sql.Date fechaSql = null;
                if (producto.fechaVencimiento != null && !producto.fechaVencimiento.isEmpty()) {
                    fechaSql = java.sql.Date.valueOf(producto.fechaVencimiento);
                }
                stmtUpdate.setDate(5, fechaSql);
                stmtUpdate.setInt(6, producto.id);

                int filasAfectadas = stmtUpdate.executeUpdate();
                if (filasAfectadas > 0) {
                    response.getWriter().write("{\"status\":\"success\", \"message\":\"Producto actualizado exitosamente.\"}");
                } else {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    response.getWriter().write("{\"status\":\"error\", \"message\":\"No se encontró el producto a actualizar.\"}");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"status\":\"error\", \"message\":\"Error en la base de datos.\"}");
        }
    }
}