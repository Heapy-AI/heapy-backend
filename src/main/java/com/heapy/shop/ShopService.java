package com.heapy.shop;

import com.heapy.common.exception.ErrorCode;
import com.heapy.common.exception.HeapyException;
import com.heapy.shop.ShopModels.Item;
import com.heapy.shop.ShopModels.Purchase;
import com.heapy.shop.ShopModels.PurchaseResult;
import com.heapy.shop.ShopModels.Wallet;
import com.heapy.shop.ShopModels.Wardrobe;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 구매·환불·착용은 미션 완료와 같은 사용자 행 잠금 순서를 따른다. @author 김진우 */
@Service
public class ShopService {
    private final ShopRepository repository;
    private final Clock clock;
    public ShopService(ShopRepository repository,Clock clock) { this.repository=repository; this.clock=clock; }
    public Wallet wallet(UUID user) { return new Wallet(repository.balance(user),10); }
    public List<Item> items(UUID user) { return repository.items(user,false); }
    public Wardrobe wardrobe(UUID user) {
        var items=repository.items(user,true);
        return new Wardrobe(items,items.stream().filter(Item::equipped).map(Item::itemId).findFirst().orElse(null));
    }
    public List<Purchase> purchases(UUID user,int limit,int offset) {
        if(limit<1 || limit>100 || offset<0) throw new HeapyException(ErrorCode.INVALID_INPUT);
        return repository.purchases(user,limit,offset);
    }
    @Transactional
    public PurchaseResult buy(UUID user,String item,UUID key) {
        lock(user);
        var previous=repository.byKey(user,key);
        if(previous!=null) {
            if(!previous.itemId().equals(item)) throw new HeapyException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            return new PurchaseResult(previous,repository.balance(user));
        }
        Integer price=repository.price(item);
        if(price==null) throw new HeapyException(ErrorCode.SHOP_ITEM_NOT_FOUND);
        if(repository.owns(user,item)) throw new HeapyException(ErrorCode.SHOP_ALREADY_OWNED);
        if(repository.balance(user)<price) throw new HeapyException(ErrorCode.SHOP_INSUFFICIENT_COINS);
        UUID id=UUID.randomUUID();
        repository.purchase(user,item,key,id,price,clock.instant());
        return new PurchaseResult(repository.byId(user,id),repository.balance(user));
    }
    @Transactional
    public PurchaseResult cancel(UUID user,UUID id) {
        lock(user);
        var purchase=repository.byId(user,id);
        if(purchase==null) throw new HeapyException(ErrorCode.RESOURCE_NOT_FOUND);
        if(purchase.cancelledAt()==null) {
            if(!clock.instant().isBefore(purchase.cancelUntil())) throw new HeapyException(ErrorCode.SHOP_REFUND_EXPIRED);
            repository.cancel(user,purchase,clock.instant());
        }
        return new PurchaseResult(repository.byId(user,id),repository.balance(user));
    }
    @Transactional
    public Wardrobe equip(UUID user,String item) {
        lock(user);
        if(!repository.owns(user,item)) throw new HeapyException(ErrorCode.SHOP_NOT_OWNED);
        repository.equip(user,item);
        return wardrobe(user);
    }
    @Transactional
    public Wardrobe unequip(UUID user) {
        lock(user);
        repository.unequip(user);
        return wardrobe(user);
    }
    private void lock(UUID user) {
        if(!repository.lockUser(user)) throw new HeapyException(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
