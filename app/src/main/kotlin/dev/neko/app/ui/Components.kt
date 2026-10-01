package dev.neko.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.neko.core.*
import java.text.NumberFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

fun rupees(paise:Long):String=NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).apply{maximumFractionDigits=if(paise%100==0L)0 else 2}.format(paise/100.0)
fun dateText(time:Long):String=DateTimeFormatter.ofPattern("d MMM · h:mm a",Locale.ENGLISH).format(Instant.ofEpochMilli(time).atZone(Ledger.india))
@Composable fun ScreenTitle(title:String,subtitle:String?=null,action:(@Composable ()->Unit)?=null){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.headlineLarge);if(subtitle!=null)Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)};action?.invoke()}
}
@Composable fun SectionTitle(title:String,action:(@Composable ()->Unit)?=null){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));action?.invoke()}}
@Composable fun Panel(modifier:Modifier=Modifier,color:Color=MaterialTheme.colorScheme.surface,content:@Composable ColumnScope.()->Unit){Surface(modifier,shape=NekoTokens.CardShape,color=color){Column(Modifier.padding(NekoTokens.Card),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)}}
@Composable fun StatusPill(text:String,good:Boolean=true){Surface(shape=CircleShape,color=if(good)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant){Row(Modifier.padding(horizontal=12.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){Box(Modifier.size(6.dp).background(if(good)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,CircleShape));Text(text,style=MaterialTheme.typography.labelSmall,color=if(good)MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)}}}
@Composable fun PrimaryButton(text:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true){Button(onClick,modifier.heightIn(min=NekoTokens.Touch),enabled=enabled,shape=NekoTokens.ControlShape){Text(text)}}
@Composable fun EmptyState(title:String,body:String,icon:ImageVector=Icons.Outlined.ReceiptLong,action:(@Composable ()->Unit)?=null){Panel(Modifier.fillMaxWidth()){Icon(icon,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(32.dp));Text(title,style=MaterialTheme.typography.titleLarge);Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant);action?.invoke()}}
@Composable fun TransactionRow(tx:Transaction,onClick:()->Unit){
    Surface(onClick=onClick,shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()){
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Surface(shape=RoundedCornerShape(15.dp),color=MaterialTheme.colorScheme.primaryContainer){Icon(categoryIcon(tx.category),null,Modifier.padding(12.dp).size(22.dp),tint=MaterialTheme.colorScheme.onPrimaryContainer)}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
                Text(tx.merchant,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(tx.account,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(if(tx.review==ReviewStatus.DRAFT)"Needs review"else if(tx.transferId!=null)"Own transfer"else tx.category.label,style=MaterialTheme.typography.labelSmall,color=if(tx.review==ReviewStatus.DRAFT)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment=Alignment.End){Text((if(tx.direction==Direction.CREDIT)"+"else"−")+rupees(tx.amountPaise),fontWeight=FontWeight.SemiBold,color=if(tx.direction==Direction.CREDIT)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface);Text(if(tx.status!=PaymentStatus.POSTED)tx.status.name.lowercase()else dateText(tx.occurredAt).substringBefore(" ·"),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
    }
}
fun categoryIcon(category:Category):ImageVector=when(category){Category.FOOD->Icons.Outlined.Restaurant;Category.GROCERIES->Icons.Outlined.ShoppingBasket;Category.TRANSPORT->Icons.Outlined.DirectionsTransit;Category.SHOPPING->Icons.Outlined.ShoppingBag;Category.BILLS->Icons.Outlined.Bolt;Category.HEALTH->Icons.Outlined.LocalHospital;Category.ENTERTAINMENT->Icons.Outlined.Movie;Category.RENT->Icons.Outlined.Home;Category.INCOME->Icons.Outlined.SouthWest;Category.REFUND->Icons.Outlined.Undo;Category.TRANSFER->Icons.Outlined.SwapHoriz;Category.OTHER->Icons.Outlined.Payments}
