package com.bms.service;

import com.bms.dto.request.PriceListRequest;
import com.bms.entity.PriceList;
import com.bms.entity.PriceListItem;
import com.bms.entity.Product;
import com.bms.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2: saving an edited price list must not re-create the lines it kept.
 *
 * <p>The old code emptied the list and built every line again, which is the one thing a
 * {@code (price_list_id, product_id)} unique rule cannot survive: Hibernate writes the new row
 * for a product before it deletes the old one, so the shop that kept half the list and changed
 * half the prices got an error instead of a save. A line that stays is now the same row with a
 * new price, and only the lines that leave are removed.
 */
@DataJpaTest
@ContextConfiguration(classes = PriceListServiceTest.JpaConfig.class)
@Import({PriceListService.class, ShopTimeService.class})
class PriceListServiceTest {

    @SpringBootConfiguration
    @EnableJpaRepositories(basePackages = "com.bms.repository")
    @EntityScan(basePackages = "com.bms.entity")
    static class JpaConfig {
    }

    @Autowired private TestEntityManager em;
    @Autowired private PriceListService priceListService;

    private Product rice;
    private Product beans;
    private Product oil;

    @BeforeEach
    void seedThreeProducts() {
        rice = product("P2-RICE", "Rice 5kg", "8.00");
        beans = product("P2-BEANS", "Beans 1kg", "15.00");
        oil = product("P2-OIL", "Oil 5L", "4.00");
        em.flush();
    }

    private Product product(String sku, String name, String cost) {
        Product product = new Product();
        product.setSku(sku);
        product.setName(name);
        product.setUnitPrice(new BigDecimal(cost));
        product.setCostPrice(new BigDecimal(cost));
        product.setStockQuantity(0);
        em.persist(product);
        return product;
    }

    /** A line of a price list: which product, at what price. */
    private record Line(Product product, String price) {
    }

    private PriceListRequest request(String name, Line... lines) {
        PriceListRequest request = new PriceListRequest();
        request.setName(name);
        List<PriceListRequest.Item> items = new ArrayList<>();
        for (Line line : lines) {
            PriceListRequest.Item item = new PriceListRequest.Item();
            item.setProductId(line.product().getId());
            item.setPrice(new BigDecimal(line.price()));
            items.add(item);
        }
        request.setItems(items);
        return request;
    }

    private Map<Long, PriceListItem> linesOf(PriceList list) {
        return list.getItems().stream()
                .collect(Collectors.toMap(item -> item.getProduct().getId(), Function.identity()));
    }

    @Test
    void anEditedListKeepsTheLineThatStayedAndSwapsTheOneThatLeft() {
        PriceList created = priceListService.create(request("Wholesale",
                new Line(rice, "10.00"), new Line(beans, "20.00")));
        em.flush();
        Long listId = created.getId();
        Long riceRow = linesOf(created).get(rice.getId()).getId();

        PriceList updated = priceListService.update(listId, request("Wholesale",
                new Line(rice, "12.00"), new Line(oil, "5.00")));
        em.flush();

        Map<Long, PriceListItem> lines = linesOf(updated);
        assertEquals(2, lines.size(), "rice stays, beans leaves, oil arrives: " + lines.keySet());
        assertEquals(new BigDecimal("12.00"), lines.get(rice.getId()).getPrice());
        assertEquals(riceRow, lines.get(rice.getId()).getId(),
                "the line that stayed must be the same row, not a new one beside the old one");
        assertFalse(lines.containsKey(beans.getId()));

        Map<Long, PriceListItem> reread = linesOf(priceListService.getById(listId));
        assertEquals(2, reread.size(), "the removed product must be gone from the database too");
        assertEquals(new BigDecimal("12.00"), reread.get(rice.getId()).getPrice());
        assertEquals(new BigDecimal("5.00"), reread.get(oil.getId()).getPrice());
    }

    @Test
    void anEmptyItemListEmptiesTheList() {
        PriceList created = priceListService.create(request("Wholesale",
                new Line(rice, "10.00"), new Line(beans, "20.00")));
        em.flush();

        priceListService.update(created.getId(), request("Wholesale"));
        em.flush();

        assertEquals(0, priceListService.getById(created.getId()).getItems().size());
    }

    @Test
    void anUnchangedLineKeepsItsPriceAndItsRow() {
        PriceList created = priceListService.create(request("Wholesale",
                new Line(rice, "10.00"), new Line(beans, "20.00")));
        em.flush();
        Long riceRow = linesOf(created).get(rice.getId()).getId();

        priceListService.update(created.getId(), request("Wholesale",
                new Line(rice, "10.00"), new Line(beans, "20.00")));
        em.flush();

        Map<Long, PriceListItem> reread = linesOf(priceListService.getById(created.getId()));
        assertEquals(riceRow, reread.get(rice.getId()).getId());
        assertEquals(new BigDecimal("10.00"), reread.get(rice.getId()).getPrice());
        assertEquals(new BigDecimal("20.00"), reread.get(beans.getId()).getPrice());
    }

    @Test
    void theSameProductTwiceInOneRequestIsRefused() {
        BusinessException ex = assertThrows(BusinessException.class, () -> priceListService.create(
                request("Wholesale", new Line(rice, "10.00"), new Line(rice, "12.00"))));

        assertTrue(ex.getMessage().contains("only appear once"), ex.getMessage());
    }

    @Test
    void aLineBelowCostStillWarnsWithoutBlockingTheSave() {
        PriceList updated = priceListService.create(request("Wholesale",
                new Line(rice, "10.00"), new Line(beans, "20.00")));
        em.flush();

        PriceList edited = priceListService.update(updated.getId(), request("Wholesale",
                new Line(rice, "6.00"), new Line(oil, "5.00")));

        assertNotNull(edited.getBelowCostWarnings(), "the warning list is always there");
        assertEquals(1, edited.getBelowCostWarnings().size(), edited.getBelowCostWarnings().toString());
        assertTrue(edited.getBelowCostWarnings().get(0).contains("Rice 5kg"),
                edited.getBelowCostWarnings().get(0));
    }

    @Test
    void editingThePricesOfADeletedListLeavesItDeleted() {
        // Deleting a list only switches it off, so an edit that says nothing about activity has
        // to keep it off. Before this the missing flag was read as "yes, active" and the list
        // the shop had thrown away came back with its old prices.
        PriceList created = priceListService.create(request("Wholesale", new Line(rice, "10.00")));
        em.flush();
        priceListService.delete(created.getId());
        em.flush();
        em.clear();

        PriceListRequest edit = request("Wholesale", new Line(rice, "12.00"));
        edit.setActive(null);
        PriceList saved = priceListService.update(created.getId(), edit);
        em.flush();

        assertEquals(Boolean.FALSE, priceListService.getById(created.getId()).getIsActive(),
                "a price edit must not resurrect a deleted list");
        assertEquals(new BigDecimal("12.00"), linesOf(saved).get(rice.getId()).getPrice(),
                "while the price the shop asked for is still saved");
    }

    @Test
    void askingForActiveBringsADeletedListBack() {
        PriceList created = priceListService.create(request("Wholesale", new Line(rice, "10.00")));
        em.flush();
        priceListService.delete(created.getId());
        em.flush();
        em.clear();

        PriceListRequest reactivate = request("Wholesale", new Line(rice, "10.00"));
        reactivate.setActive(true);
        priceListService.update(created.getId(), reactivate);
        em.flush();

        assertEquals(Boolean.TRUE, priceListService.getById(created.getId()).getIsActive());
    }

    @Test
    void aNewListStartsActiveWhenNobodySaysOtherwise() {
        PriceListRequest withoutFlag = request("Wholesale", new Line(rice, "10.00"));
        withoutFlag.setActive(null);

        assertEquals(Boolean.TRUE, priceListService.create(withoutFlag).getIsActive());
    }

    @Test
    void aRenameOntoAnotherListsNameIsRefusedHoweverItIsCapitalised() {
        priceListService.create(request("Wholesale", new Line(rice, "10.00")));
        PriceList other = priceListService.create(request("Staff", new Line(beans, "20.00")));
        em.flush();

        BusinessException lower = assertThrows(BusinessException.class,
                () -> priceListService.update(other.getId(), request("wholesale", new Line(beans, "20.00"))));
        assertTrue(lower.getMessage().contains("already exists"), lower.getMessage());

        BusinessException spaced = assertThrows(BusinessException.class,
                () -> priceListService.update(other.getId(), request(" WHOLESALE ", new Line(beans, "20.00"))));
        assertTrue(spaced.getMessage().contains("already exists"), spaced.getMessage());
    }

    @Test
    void aListMayKeepItsOwnNameWhateverTheCase() {
        PriceList created = priceListService.create(request("Wholesale", new Line(rice, "10.00")));
        em.flush();

        PriceList saved = priceListService.update(created.getId(),
                request("wholesale", new Line(rice, "12.00")));

        assertEquals("wholesale", saved.getName(), "the stored name is the trimmed one, case and all");
        assertEquals(new BigDecimal("12.00"), linesOf(saved).get(rice.getId()).getPrice());
    }
}
