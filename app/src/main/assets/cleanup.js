(() => {
  'use strict';
  if (window.__simpleCastCleanup) return;
  window.__simpleCastCleanup = true;
  const marker = 'data-simplecast-hidden-ad';
  const selectors = [
    'ins.adsbygoogle', '[data-ad-slot]', '[data-ad-client]',
    '.advertisement', '.advertising', '.ad-banner', '.ad-container', '.ad-slot', '.ad-unit', '.adsbox',
    '#ad-banner', '#ad-container', '#ad-overlay', '#popup-ad', '.popup-ad', '.ad-popup',
    '[id^="google_ads_iframe"]', '[id^="aswift_"]', 'iframe[data-ad]', 'iframe[id^="ad_iframe"]'
  ].join(',');
  let scheduled = false;
  function clean() {
    scheduled = false;
    if (!document.documentElement) return;
    let style = document.getElementById('__simplecast_ad_style');
    if (!style) {
      style = document.createElement('style'); style.id = '__simplecast_ad_style';
      style.textContent = '[' + marker + ']{display:none!important}';
      (document.head || document.documentElement).appendChild(style);
    }
    document.querySelectorAll(selectors + ',[' + marker + ']').forEach(element => {
      // Broad names can also wrap a legitimate player. Keep those containers visible.
      if (!element.matches(selectors) || element.matches('video') || element.querySelector('video')
          || (!element.matches('iframe') && element.querySelector('iframe:not([id^="google_ads_iframe"]):not([id^="aswift_"]):not([data-ad]):not([id^="ad_iframe"])'))) {
        element.removeAttribute(marker); return;
      }
      if (!element.hasAttribute(marker)) element.setAttribute(marker, '');
    });
  }
  function schedule() {
    if (scheduled) return;
    scheduled = true; setTimeout(clean, 100);
  }
  new MutationObserver(schedule).observe(document, {childList:true, subtree:true, attributes:true, attributeFilter:['id','class','src','data-ad-slot','data-ad-client','data-ad']});
  document.addEventListener('DOMContentLoaded', clean);
  clean();
})();
