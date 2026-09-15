package com.heapy.shop;

import com.heapy.shop.ShopModels.Item;
import com.heapy.shop.ShopModels.Purchase;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 코인 잔액은 원장에서 계산하고 사용자 잠금은 서비스 트랜잭션에서 유지한다. @author 김진우 */
@Repository
public class ShopRepository {
    private final JdbcTemplate jdbc;
    public ShopRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public boolean lockUser(UUID user) {
        return !jdbc.queryForList("select user_id from public.users where user_id=? for update", user).isEmpty();
    }
    public long balance(UUID user) {
        return jdbc.queryForObject("select coalesce(sum(amount),0) from public.coin_ledger where user_id=?", Long.class, user);
    }
    public List<Item> items(UUID user, boolean ownedOnly) {
        return jdbc.query("""
                select s.*, i.item_id is not null as owned, e.item_id is not null as equipped
                from public.shop_items s
                left join public.user_inventory i on i.item_id=s.item_id and i.user_id=?
                left join public.equipped_items e on e.item_id=s.item_id and e.user_id=?
                where (? and i.item_id is not null) or (not ? and s.active)
                order by s.display_order,s.item_id
                """, (r,n) -> new Item(r.getString("item_id"),r.getString("name"),r.getString("slot"),
                r.getInt("price"),r.getString("asset_key"),r.getBoolean("owned"),r.getBoolean("equipped")),
                user,user,ownedOnly,ownedOnly);
    }
    public Integer price(String item) {
        var values=jdbc.queryForList("select price from public.shop_items where item_id=? and active", Integer.class,item);
        return values.isEmpty()?null:values.getFirst();
    }
    public boolean owns(UUID user,String item) {
        return !jdbc.queryForList("select item_id from public.user_inventory where user_id=? and item_id=?",user,item).isEmpty();
    }
    public Purchase byKey(UUID user,UUID key) { return purchase(user,"p.idempotency_key",key); }
    public Purchase byId(UUID user,UUID id) { return purchase(user,"p.purchase_id",id); }
    private Purchase purchase(UUID user,String field,UUID value) {
        var rows=jdbc.query("""
                select p.*, -l.amount as spent_coins from public.shop_purchases p
                join public.coin_ledger l on l.purchase_id=p.purchase_id and l.kind='purchase'
                where p.user_id=? and
                """ + field + "=?",ShopRepository::mapPurchase,user,value);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public List<Purchase> purchases(UUID user,int limit,int offset) {
        return jdbc.query("""
                select p.*, -l.amount as spent_coins from public.shop_purchases p
                join public.coin_ledger l on l.purchase_id=p.purchase_id and l.kind='purchase'
                where p.user_id=? order by p.purchased_at desc,p.purchase_id limit ? offset ?
                """,ShopRepository::mapPurchase,user,limit,offset);
    }
    private static Purchase mapPurchase(ResultSet r,int n) throws SQLException {
        var cancelled=r.getTimestamp("cancelled_at");
        return new Purchase(r.getObject("purchase_id",UUID.class),r.getString("item_id"),
                r.getTimestamp("purchased_at").toInstant(),r.getTimestamp("cancel_until").toInstant(),
                cancelled==null?null:cancelled.toInstant(),r.getInt("spent_coins"));
    }
    public void purchase(UUID user,String item,UUID key,UUID id,int price,Instant now) {
        jdbc.update("""
                insert into public.shop_purchases(purchase_id,user_id,item_id,idempotency_key,purchased_at,cancel_until)
                values (?,?,?,?,?,?)
                """,id,user,item,key,Timestamp.from(now),Timestamp.from(now.plusSeconds(7*86400)));
        jdbc.update("insert into public.coin_ledger(user_id,amount,kind,purchase_id) values (?,?,'purchase',?)",user,-price,id);
        jdbc.update("insert into public.user_inventory(user_id,item_id,purchase_id) values (?,?,?)",user,item,id);
    }
    public void cancel(UUID user,Purchase purchase,Instant now) {
        jdbc.update("delete from public.equipped_items where user_id=? and item_id=?",user,purchase.itemId());
        jdbc.update("delete from public.user_inventory where user_id=? and purchase_id=?",user,purchase.purchaseId());
        jdbc.update("update public.shop_purchases set cancelled_at=? where user_id=? and purchase_id=?",Timestamp.from(now),user,purchase.purchaseId());
        jdbc.update("insert into public.coin_ledger(user_id,amount,kind,purchase_id) values (?,?,'refund',?)",user,purchase.spentCoins(),purchase.purchaseId());
    }
    public void equip(UUID user,String item) {
        jdbc.update("delete from public.equipped_items where user_id=?",user);
        jdbc.update("insert into public.equipped_items(user_id,item_id) values (?,?)",user,item);
    }
    public void unequip(UUID user) { jdbc.update("delete from public.equipped_items where user_id=?",user); }
}
