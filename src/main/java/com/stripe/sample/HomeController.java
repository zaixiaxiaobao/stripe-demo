package com.stripe.sample;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {
  /** 访问根地址时进入商品页。 */
  @GetMapping("/")
  public String home() {
    return "redirect:/checkout.html";
  }
}
