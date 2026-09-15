package com.heapy.shop;

import com.heapy.common.response.ApiResponse;
import com.heapy.security.AuthenticatedUser;
import com.heapy.shop.ShopModels.EquipRequest;
import com.heapy.shop.ShopModels.Item;
import com.heapy.shop.ShopModels.Purchase;
import com.heapy.shop.ShopModels.PurchaseRequest;
import com.heapy.shop.ShopModels.PurchaseResult;
import com.heapy.shop.ShopModels.Wallet;
import com.heapy.shop.ShopModels.Wardrobe;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 본인 코인과 보유 의상만 접근하는 상점 API. @author 김진우 */
@RestController
@SecurityRequirement(name="bearerAuth")
@Tag(name="미션 코디 상점",description="코인, 구매·취소, 옷장, 단일 의상 착용")
public class ShopController {
    private final ShopService service;
    public ShopController(ShopService service) { this.service=service; }
    @GetMapping("/api/coins")
    public ResponseEntity<ApiResponse<Wallet>> wallet(@AuthenticationPrincipal Jwt jwt) {
        return ok(service.wallet(AuthenticatedUser.id(jwt)));
    }
    @GetMapping("/api/shop/items")
    public ResponseEntity<ApiResponse<List<Item>>> items(@AuthenticationPrincipal Jwt jwt) {
        return ok(service.items(AuthenticatedUser.id(jwt)));
    }
    @PostMapping("/api/shop/purchases")
    public ResponseEntity<ApiResponse<PurchaseResult>> buy(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody PurchaseRequest request) {
        return ok(service.buy(AuthenticatedUser.id(jwt),request.itemId(),key));
    }
    @GetMapping("/api/shop/purchases")
    public ResponseEntity<ApiResponse<List<Purchase>>> purchases(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue="20") int limit,@RequestParam(defaultValue="0") int offset) {
        return ok(service.purchases(AuthenticatedUser.id(jwt),limit,offset));
    }
    @PostMapping("/api/shop/purchases/{purchaseId}/cancel")
    public ResponseEntity<ApiResponse<PurchaseResult>> cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID purchaseId) {
        return ok(service.cancel(AuthenticatedUser.id(jwt),purchaseId));
    }
    @GetMapping("/api/wardrobe")
    public ResponseEntity<ApiResponse<Wardrobe>> wardrobe(@AuthenticationPrincipal Jwt jwt) {
        return ok(service.wardrobe(AuthenticatedUser.id(jwt)));
    }
    @PutMapping("/api/wardrobe/equipped")
    public ResponseEntity<ApiResponse<Wardrobe>> equip(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody EquipRequest request) {
        return ok(service.equip(AuthenticatedUser.id(jwt),request.itemId()));
    }
    @DeleteMapping("/api/wardrobe/equipped")
    public ResponseEntity<ApiResponse<Wardrobe>> unequip(@AuthenticationPrincipal Jwt jwt) {
        return ok(service.unequip(AuthenticatedUser.id(jwt)));
    }
    private static <T> ResponseEntity<ApiResponse<T>> ok(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(value,"처리했습니다."));
    }
}
